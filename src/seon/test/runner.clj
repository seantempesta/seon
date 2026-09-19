(ns seon.test.runner
  "Run the JVM gate and optionally commit per-test result facts."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.test :as test]
            [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error :as error]
            [seon.fn :as seon.fn]
            [seon.instrument :as instrument]
            [seon.id :as id]
            [seon.program :as program]
            [seon.render :as render]
            [seon.render.value :as value]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test.arm :as test.arm]
            [seon.test.bounds :as bounds]
            [seon.test.selection :as selection]
            [seon.test.cache :as cache])
  (:import (java.io BufferedReader PrintWriter StringWriter)
           (java.lang Process ProcessBuilder$Redirect ProcessHandle Runtime Thread)
           (java.nio.charset StandardCharsets)
           (java.lang.management ManagementFactory ThreadInfo)
           (java.time Instant)
           (java.util.concurrent CompletableFuture Executors
                                 ExecutionException LinkedBlockingQueue
                                 ThreadFactory TimeUnit TimeoutException))
  (:gen-class))

(defn var-reference?
  "True for a host or SCI Var reference."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (or (var? value) (sci.utils/var? value)))

;;; LOAD-CYCLE BOUNDARIES. `seon.cluster` requires `seon.test.runner`
;;; transitively, so this namespace cannot require it back. One resolution
;;; per var, realized at first use, instead of a `requiring-resolve` on every
;;; call (AGENTS §2.1).
(defonce ^:private cluster-start!
  (delay (requiring-resolve 'seon.cluster/start!)))
(defonce ^:private cluster-stop!
  (delay (requiring-resolve 'seon.cluster/stop!)))
(defonce ^:private cluster-refresh-source!
  (delay (requiring-resolve 'seon.cluster/refresh-source!)))
(defonce ^:private cluster-source-artifact-file
  (delay (requiring-resolve 'seon.cluster/source-artifact-file)))
(defonce ^:private cluster-source-progress
  (delay (requiring-resolve 'seon.cluster/*source-progress!*)))
(defonce ^:private export-reidentify!
  (delay (requiring-resolve 'seon.cluster.export/reidentify!)))

(def var-generator
  "Finite representatives for the closed host/SCI Var representation sum."
  (gen/elements [#'var-reference? #'var-generator]))

(schema/register-core-predicate! 'seon.test.runner/var-reference?
                                 var-reference?)

(def class-loader-generator
  (gen/return (clojure.lang.RT/baseLoader)))

(defn class-loader?
  "Whether a supplied runtime value is a JVM class loader."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? ClassLoader value))

(schema/register-core-predicate! 'seon.test.runner/class-loader? class-loader?)

(defn- var-symbol
  [test-var]
  (when test-var
    (let [{:keys [name ns]} (meta test-var)]
      (when (and name ns)
        (symbol (str ns) (str name))))))

(defn- on-caller-loader
  "Wrap `task` so it runs under the submitting thread's classloader and
  dynamic frame.

  A virtual thread's context classloader is not Clojure's dynamic
  loader, so a lazy require on an executor task compiles record classes
  onto a SIBLING loader chain — the main thread's concurrent loads then
  fail with ClassNotFoundException or 'namespace not found' for
  whichever source-compiled dependency loses the race (observed live:
  sci one run, clj-kondo's inlined tools.reader the next). Every
  executor submission in this runner pins the caller's loader first.

  A plain `fn` conveyed the loader and NOTHING ELSE, so everything the
  caller handed the work — the armed schema projection above all — was
  absent on the executor thread, and reads there fell back to Datahike's
  base attributes. `bound-fn*` captures the submitting thread's frame at
  wrap time, which is the caller's."
  ^java.util.concurrent.Callable [task]
  (let [loader (.getContextClassLoader (Thread/currentThread))
        conveyed (bound-fn* task)]
    (fn []
      (.setContextClassLoader (Thread/currentThread) loader)
      (conveyed))))

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

(defn- report-options
  []
  (let [configuration (config/defaults)]
    (assoc (select-keys configuration
                        [:seon.print/length :seon.print/level])
           :seon.render/profile
           (render/agent-render-profile configuration))))

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

(defn- throwable-text
  "One reported throwable as its own diagnostic body.

  Its complete message plus frames bounded by the DECLARED
  `:seon.print/length`. THE SIGNATURE IS NOT PART OF THIS TEXT: the cause's
  signature is the error face's own trailer, named once per distinct cause by
  `report-error!`, and a second copy carried inside the reported value made
  one whole face name the same signature twice."
  [options ^Throwable failure]
  (str/trim-newline
   (with-out-str
     (println (str (.getName (class failure)) ": "
                   (or (ex-message failure) "")))
     (doseq [frame (take (:seon.print/length options)
                         (.getStackTrace failure))]
       (println "    at" frame)))))

(defn- throwable-face
  "A whole face for a throwable NO error face of its own will carry.

  A worker task that fails outside a test Var has no `report-error!` trailer,
  so its signature is named here."
  [options ^Throwable failure signature]
  (cond-> (str (throwable-text options failure) "\n")
    signature (str "  signature: " signature "\n")))

(defn- report-value
  "One reported assertion value, rendered for a human reading the gate log.

  A THROWABLE IS A DIAGNOSTIC, NOT AN AGENT PROJECTION. The AI profile's token
  budget is shared across the whole rendered value, so a large `ex-data`
  squeezes the exception's own message out of the report: a fixture refusal
  arrived as `{:seon.print/omitted 3458 :seon.print/prefix \"Fixture write was
  refused at the write: …\"}` and named less than the runner knew. A check that
  reports less than it observed is the failure class this project keeps
  meeting, so the reported throwable is plain text — its complete message plus
  frames bounded by the DECLARED print length, never the agent's budget
  (AGENTS §2.4). The identity path (`printable`) has always done this. It is
  the throwable's BODY (`throwable-text`), not a whole face: the error face's
  signature trailer belongs to `report-error!`, which names it once per
  distinct cause.

  Ordinary values keep the profile: they are the ones that can be a whole
  database value."
  [options reported-value]
  (if (instance? Throwable reported-value)
    (throwable-text options reported-value)
    (value/render-ai
     {:seon.render/value reported-value
      :seon.render.value/root 'seon.test.runner/assertion
      :seon.render/profile (or (:seon.render/profile options)
                               (:seon.render/profile (report-options)))})))

(defn- failure-message
  [options event]
  (->> [(when (seq test/*testing-contexts*)
          (test/testing-contexts-str))
        (:message event)
        (when (contains? event :expected)
          (str "expected: " (report-value options (:expected event))))
        (when (contains? event :actual)
          (str "actual: " (report-value options (:actual event))))]
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
                  {:seon.test/sym test-symbol
                   :seon.test/pass-count 0
                   :seon.test/fail-count 0
                   :seon.test/error-count 0
                   :seon.test.member/began? false
                   :seon.test.member/ended? false
                   ::failure-messages []
                   ::failure-identities #{}}))))

(defn- failure-report [event]
  (cond-> {:seon.test.failure/type (:type event)}
    (find event :expected) (assoc :seon.test.failure/expected (printable {} (:expected event)))
    (find event :actual) (assoc :seon.test.failure/actual (printable {} (:actual event)))
    (:message event) (assoc :seon.test.failure/message (str (:message event)))
    (seq test/*testing-contexts*)
    (assoc :seon.test.failure/contexts (mapv vector (range) (reverse test/*testing-contexts*)))
    (:file event) (assoc :seon.test.failure/reported-file (str (:file event)))
    (and (integer? (:line event)) (pos? (:line event)))
    (assoc :seon.test.failure/line (:line event))
    (event-signature event) (assoc :seon.test.failure/signature (event-signature event))
    (instance? Throwable (:actual event))
    (assoc :seon.test.failure/throwable (symbol (.getName (class (:actual event)))))))

(defn- capture-event!
  [options capture selected-namespaces event]
  (when-let [test-symbol (event-symbol event)]
    (when (contains? selected-namespaces (symbol (namespace test-symbol)))
      (swap! capture
             (fn [current]
               (let [current (ensure-result current test-symbol)
                     event-type (:type event)]
                 (case event-type
                   :begin-test-var
                   (assoc-in current [::results test-symbol :seon.test.member/began?] true)

                   :end-test-var
                   (assoc-in current [::results test-symbol :seon.test.member/ended?] true)

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
                     (cond-> (-> current
                                 (update-in [::results test-symbol :seon.test.failure/reports]
                                            (fnil conj [])
                                            (assoc (failure-report event)
                                                   :seon.test/failure-identity failure-id))
                                 (update-in
                                        [::results test-symbol
                                         (if (= :fail event-type)
                                           :seon.test/fail-count
                                           :seon.test/error-count)]
                                        inc))
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
    (println "\nERROR in" (test/testing-vars-str event))
    (println (failure-message options event))
    (when signature
      (println "  signature:" signature))))

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
    (if (#{:fail :error} (:type event))
      (test/with-test-out
        (test/inc-report-counter (:type event))
        (println (str "\n" (str/upper-case (name (:type event))) " in")
                 (test/testing-vars-str event))
        (println (failure-message options event)))
      (default-report event))))

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
      ;; assoc, never reset: the silence horizon's declared allowances live
      ;; in this same atom and must survive every announcement.
      (swap! progress assoc
             ::description description
             ::at-nanos (System/nanoTime)
             ::at at)
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
    (let [var-meta (meta (:var event))
          declaration (merge (meta (:ns var-meta)) var-meta)
          allowance (if (:seon.test/long declaration)
                      (quot (+ (or (:seon.test/long-ms declaration) 0) 999) 1000)
                      0)]
      (swap! progress assoc-in [::silence-allowances :test-body]
             (max 0 (- allowance bounds/ordinary-exchange-seconds)))
      (announce! progress (str "BEGIN test " (event-symbol event))))

    :end-test-var
    (do
      (announce! progress (str "END test " (event-symbol event)))
      (swap! progress update ::silence-allowances dissoc :test-body))

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
  (bounds/silence-seconds (into {} (System/getenv))))

(defn- exchange-bound-seconds
  "The per-exchange bound: strictly inside the suite silence horizon.

  Both cannot sit at the same 300 s — when a task legitimately exceeds
  it, the suite watchdog raced the typed task bound and sometimes won,
  killing the run at exit 124 with the coordinator parked mid-exchange.
  The exchange fires first, converts to an attributed result, and that
  result IS reporter progress, so the watchdog never needs to."
  []
  (- (silence-seconds) bounds/reporter-grace-seconds))

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
            (let [{::keys [at-nanos silence-allowances]} @progress
                  ;; A declared-long task legitimately runs silently past the
                  ;; ordinary horizon: the declaration widens the horizon
                  ;; exactly while that task is in flight, so the watchdog
                  ;; never races a bound the program itself declared.
                  limit (+ silence-limit-seconds
                           (reduce max 0 (vals silence-allowances)))
                  silent-nanos (- (System/nanoTime) at-nanos)]
              (when (and (>= silent-nanos
                             (.toNanos TimeUnit/SECONDS limit))
                         (compare-and-set! fired? false true))
                (fire-liveness-backstop!
                 progress limit suite-start)))))]
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

(declare ambient-snapshot ambient-drift run-selected-tests)

(defn run-vars!
  "Capture selected host or SCI Vars together, applying namespace fixtures once."
  {:malli/schema
   [:=> [:cat [:vector :seon.test/var] :seon.db/custody-request]
    [:or [:vector :seon.test.runner/captured-result]
     :seon.test/not-runnable-error]]}
  [test-vars custody]
  (if-let [unrunnable (first (remove #(ifn? (:test (meta %))) test-vars))]
    {:seon.error/kind ::not-runnable
     :seon.test/not-runnable (str unrunnable)
     :seon.error/message "The supplied Var has no clojure.test function."}
    (let [selected-namespaces (set (map (comp symbol namespace symbol var-symbol) test-vars))
          options (report-options)
          capture (atom {::order [] ::results {}})
          reported-signatures (atom #{})
          default-report (.getRawRoot #'test/report)
          before (ambient-snapshot)]
      (binding [test/*report-counters* (ref test/*initial-report-counters*)
                test/*testing-vars* ()
                test/*testing-contexts* ()
                test/report
                (fn [event]
                  (capture-and-report-event!
                   options capture selected-namespaces default-report
                   reported-signatures event))]
        (db/call-with-custody custody
          #(run-selected-tests (sort selected-namespaces) test-vars
                               (:seon.sci.eval/ctx custody))))
      (let [results (captured-results @capture)
            drift (ambient-drift before (ambient-snapshot))]
        (mapv (fn [result]
                (cond-> result
                  (seq drift) (update :seon.test/error-count (fnil inc 0))
                  (seq drift) (update :seon.test/failure-message
                                      #(str (when % (str % "\n"))
                                            "Worker-global state changed: " (pr-str drift)))))
              results)))))

(defn run-var!
  "Run one host or SCI test Var under the custody its caller hands it, and
  return its captured assertion result.

  This is the same capture and reporter path used by `bin/test`; it performs
  no database write. Each invocation owns its counters, test context and terminal
  reporter; nested runs cannot contribute evidence to their caller.
  `commit-results!` is the sole completion writer.

  THE CUSTODY IS A VALUE, NEVER A RE-READ. `:seon.db/connection` on the
  supplied request is the cluster whose work this run IS: an agent running
  its own declared tests inside its evaluation reaches that cluster through
  the elided `seon.db` arities, exactly as the rest of its evaluation does.
  The one-argument arity hands none — a host REPL or a `bin/test` worker
  running the same Var is nobody's cluster work, and an elided arity there
  refuses loudly and names what it needed.

  Whatever custody this thread INHERITED decides nothing. A `bound-fn` or a
  virtual thread carries the bindings of whoever created it, so before this
  seam took the connection as a value an agent's own test silently read and
  wrote whichever cluster happened to be in scope — on 2026-09-17 that put a
  test's synthetic schema rows into `default`'s datoms and every later write
  on the cluster was refused, and the repair then left the agent's own tests
  with no cluster at all. The caller that knows whose work a run is says so;
  this binds exactly that answer (AGENTS §2.1)."
  {:malli/schema
   [:function
    [:=> [:cat :seon.test/var]
     [:or :seon.test.runner/captured-result
      :seon.test/not-runnable-error]]
    [:=> [:cat :seon.test/var :seon.db/custody-request]
     [:or :seon.test.runner/captured-result
      :seon.test/not-runnable-error]]]}
  ([test-var] (run-var! test-var {}))
  ([test-var custody]
   (let [results (run-vars! [test-var] custody)]
     (if (:seon.error/kind results) results (first results)))))

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
           :seon.test/sym (var-symbol test-var)
           ::marker marker-attribute
           ::value marker :seon.test.runner/invalid-marker-reason true})))
      marker)))

(defn- manifest-rows
  [manifest]
  (vec (mapcat :seon.fn.file/rows (:seon.fn.manifest/artifacts manifest))))

(defn- long-declarations
  "The declared long tests, as indexed program rows keyed by test symbol.

  ONE derivation: `:seon.test/long` and its optional `:seon.test/long-ms`
  allowance are lifted onto the test row at the two definition seams
  (`seon.fn/var-row`, `seon.sci.eval`), so selection and the per-exchange
  bound both read the same fact. The runner no longer re-reads Var metadata
  for this marker; `verify-long-declarations-indexed!` is the drift check
  that keeps the row from silently answering for a Var it never indexed."
  [manifest]
  (into {}
        (keep (fn [row]
                (when-let [reason (:seon.test/long row)]
                  [(:seon.test/sym row)
                   (cond-> {:seon.test/long reason}
                     (:seon.test/long-ms row)
                     (assoc :seon.test/long-ms (:seon.test/long-ms row)))])))
        (manifest-rows manifest)))

(def ^:private test-source-root
  "The declared source root whose artifacts hold this program's tests.

  Taken from `seon.fn/source-roots`, the declaration the manifest itself is
  built over, so namespace discovery keys on a root the program graph admits
  rather than on a filename suffix. The declaration is a plain vector, so the
  `delay` defers only the REFUSAL — reading it must never deref it."
  (delay
    (let [roots seon.fn/source-roots]
      (or (some #{"test"} roots)
          (throw
           (ex-info
            (str "The program source roots declare no test root: "
                 (pr-str roots) ".")
            {:seon.error/kind ::test-source-root-undeclared
             ::source-roots roots}))))))

(defn- bare-namespaces
  "Every namespace the bare gate runs, derived from the published manifest.

  THE DERIVATION, not a filename convention: a namespace is a gate member
  when the program graph holds a `:seon.ns/name` row in an artifact under the
  declared test source root, and none of that artifact's rows carry
  `:seon.test/fixture`.

  `find test -name '*_test.clj'` answered this before and was wrong in both
  directions. It MISSED `seon.repl-parity-test`, whose deftests a macro emits
  so the file indexes a namespace and no `:seon.test/sym` rows — silent
  coverage loss, absence read as health. And selecting instead on \"has test
  rows\" would ADMIT `seon.test-runner-failure-fixture`, whose
  `failing-example` asserts `(= 5 (+ 2 2))` deliberately, turning the bare
  gate permanently red. The two questions are different facts, and both are
  now declared: membership by indexed namespace, exclusion by the marker."
  [manifest]
  (let [prefix (str @test-source-root "/")
        derived
        (into (sorted-set)
              (keep (fn [artifact]
                      (when (str/starts-with?
                             (str (:seon.fn.file/relative-path artifact)) prefix)
                        (let [rows (:seon.fn.file/rows artifact)]
                          (when-not (some :seon.test/fixture rows)
                            (some :seon.ns/name rows))))))
              (:seon.fn.manifest/artifacts manifest))]
    (when (empty? derived)
      (throw
       (ex-info
        (str "The published program manifest declares no namespace under the "
             "test source root " (pr-str @test-source-root)
             ", so bare selection would run nothing and report success.")
        {:seon.error/kind ::no-bare-namespaces
         ::test-source-root @test-source-root
         ::artifact-count (count (:seon.fn.manifest/artifacts manifest))})))
    (vec derived)))

(defn- platform-declarations
  "The declared platform regressions, as indexed program rows keyed by symbol.

  The same ONE derivation `long-declarations` makes: `:seon.test/platform` is
  lifted onto the test row at both definition seams by
  `seon.program/test-marker-attributes`, so the tier partition reads the fact
  the coordinator already holds from the manifest instead of re-reading Var
  metadata for a question the program graph answers.
  `verify-platform-declarations-indexed!` is its drift check."
  [manifest]
  (into {}
        (keep (fn [row]
                (when-let [reason (:seon.test/platform row)]
                  [(:seon.test/sym row) {:seon.test/platform reason}])))
        (manifest-rows manifest)))

(defn- verify-platform-declarations-indexed!
  "Refuse when a Var declares `:seon.test/platform` and the program row does not.

  The tier partition reads the row. An unindexed declaration would run a
  platform regression in the bulk tier, losing exactly the fail-fast the tier
  exists for — absence read as health — so the drift is named here."
  [declarations test-vars]
  (let [drifted
        (into []
              (keep (fn [test-var]
                      (let [test-symbol (var-symbol test-var)
                            declared (get declarations test-symbol)
                            var-reason (marker-reason test-var :seon.test/platform)]
                        ;; Only the dangerous direction refuses, exactly as the
                        ;; long check does: a row declaring more than its Var is
                        ;; conservative on its own.
                        (when (and var-reason
                                   (not= var-reason (:seon.test/platform declared)))
                          {:seon.test/sym test-symbol
                           ::declared-on-var {:seon.test/platform var-reason}
                           ::indexed-row (or declared {})}))))
              test-vars)]
    (when (seq drifted)
      (throw
       (ex-info
        (str "The indexed program rows disagree with the declared platform "
             "regressions: " (str/join ", " (map :seon.test/sym drifted))
             ". The tier partition reads the row, so an unindexed declaration "
             "runs a platform regression in the bulk tier; publish the tree so "
             "the declaration is indexed.")
        {:seon.error/kind ::platform-declaration-drift
         ::drifted-platform-declarations drifted
         :seon.test.runner/platform-declaration-drift true}))))
  nil)

(defn- verify-long-declarations-indexed!
  "Refuse when a Var declares `:seon.test/long` and the program row does not.

  Selection and the bound read the row. An unindexed declaration would make
  the row answer NOT-LONG for a test that genuinely is one — absence read as
  health, the class this whole runner is built against — so the drift is
  named here instead of silently running a real-boot drill in the pool."
  [declarations test-vars]
  (let [drifted
        (into []
              (keep (fn [test-var]
                      (let [test-symbol (var-symbol test-var)
                            declared (get declarations test-symbol)
                            metadata (meta test-var)
                            var-reason (marker-reason test-var :seon.test/long)
                            ;; The allowance resolves exactly as its reason
                            ;; does — Var metadata, then the namespace's —
                            ;; through the same rule both indexing seams
                            ;; read. Reading the Var alone made a
                            ;; namespace-level allowance look like drift
                            ;; against the row that lifted it.
                            var-allowance
                            (:seon.test/long-ms
                             (program/test-markers metadata
                                                   (meta (:ns metadata))))]
                        ;; Only the dangerous direction refuses: a Var that
                        ;; declares while the row does not would run a long
                        ;; test under the ordinary bound. A row that declares
                        ;; more than its Var is conservative on its own.
                        (when (and (or var-reason var-allowance)
                                   (or (not= var-reason (:seon.test/long declared))
                                       (not= var-allowance
                                             (:seon.test/long-ms declared))))
                          {:seon.test/sym test-symbol
                           ::declared-on-var
                           (cond-> {} var-reason (assoc :seon.test/long var-reason)
                                   var-allowance
                                   (assoc :seon.test/long-ms var-allowance))
                           ::indexed-row (or declared {})}))))
              test-vars)]
    (when (seq drifted)
      (throw
       (ex-info
        (str "The indexed program rows disagree with the declared long tests: "
             (str/join ", " (map :seon.test/sym drifted))
             ". Selection and the per-exchange bound read the row, so an "
             "unindexed declaration runs a long test under the ordinary "
             "bound; publish the tree so the declaration is indexed.")
        {:seon.error/kind ::long-declaration-drift
         ::drifted-long-declarations drifted
         :seon.test.runner/long-declaration-drift true}))))
  nil)

(defn fixture-observation!
  "Require the declared observation before acquiring an expensive fixture.
  A direct caller supplies it in options; a test may declare it on its Var/ns."
  {:malli/schema
   [:=> [:cat :qualified-symbol
         [:map [:seon.test/fixture-observation {:optional true}
                :seon.test/fixture-observation]]]
    :seon.test/fixture-observation]}
  [fixture options]
  (let [reason (if (find options :seon.test/fixture-observation)
                 (:seon.test/fixture-observation options)
                 (some #(marker-reason % :seon.test/fixture-observation)
                       test/*testing-vars*))]
    (when-not (and (string? reason) (not (str/blank? reason)))
      (throw (ex-info "Expensive fixture requires a nonblank observation an ordinary branch cannot prove."
                      {:seon.error/kind ::missing-fixture-observation
                       ::fixture fixture
                       :seon.test/sym (var-symbol (first test/*testing-vars*))})))
    reason))

(defn- test-selection
  "Partition every test var into the platform tier, the bulk tier, and skips.

  The platform tier is the declared `:seon.test/platform` moving-part
  regression set: it runs FIRST on every invocation so a broken platform
  fails in seconds instead of poisoning the bulk. `selected-symbols` bounds
  the bulk tier to the tests one change can reach; `:all` runs every
  eligible test.

  Both markers are read from the INDEXED ROW, never from Var metadata: the
  published program graph is the authority the drift checks defend, and one
  partition reading two different sources for two markers is how a Var-only
  declaration answered for a row that never carried it."
  [namespaces {declarations ::long-declarations
               platform-declarations* ::platform-declarations
               ::keys [include-long? selected-symbols]}]
  (reduce
   (fn [selection test-var]
     (let [test-symbol (var-symbol test-var)
           long-marker (get-in declarations
                               [test-symbol :seon.test/long])
           platform (get-in platform-declarations*
                            [test-symbol :seon.test/platform])]
       (cond
         (and (not include-long?) long-marker)
         (update selection ::skipped conj
                 {::test-symbol test-symbol ::reason long-marker})

         platform
         (update selection ::platform conj test-var)

         (or (= :all selected-symbols)
             (contains? selected-symbols test-symbol))
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
  [all-vars selected-vars declarations]
  (let [declared #(get declarations (var-symbol %))
        ordinal-by-symbol
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
                (let [symbols (mapv (comp str var-symbol) task-vars)
                      reason (some->> task-vars
                                      (keep #(:seon.test/long (declared %)))
                                      seq (str/join "; "))
                      allowance (some->> task-vars
                                         (keep #(:seon.test/long-ms (declared %)))
                                         seq (apply max))]
                  (cond-> {::task-id (str (random-uuid))
                           ::task-ordinal
                           (apply min (map #(ordinal-by-symbol (var-symbol %))
                                           task-vars))
                           ::task-namespace
                           (str (ns-name (:ns (meta (first task-vars)))))
                           ::task-symbols symbols
                           ::task-long? (boolean (some declared task-vars))}
                    reason (assoc ::task-long-reason reason)
                    allowance (assoc ::task-long-ms allowance)))))
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

(defn- tests-reaching-rows
  "Select from explicit seed rows while retaining real file uncertainty."
  [artifacts seeds]
  (set (selection/reaching-tests
        (into [{:seon.fn.file/relative-path "fixture-selection"
                :seon.fn.file/rows (vec seeds)}]
              artifacts)
        ["fixture-selection"])))

;;; ---------------------------------------------------------------------------
;;; The platform tier declares no destructive drill
;;; ---------------------------------------------------------------------------

(defn- destructive-owner-rows
  "The program rows declaring `:seon.fn/destroys`, or a refusal.

  THE ONE DERIVATION of \"which functions destroy\", over the declaration each
  owner carries in its own metadata at its definition. There is no owner
  roster here or anywhere else: a rename moves with the definition, and a
  declaration added to a new owner is picked up by indexing alone.

  `seon.cluster.store/create-store!` deliberately declares nothing: every
  `open-store!` reaches it, it deletes only its own incomplete genesis and now
  refuses a complete store outright, so declaring it would empty the platform
  tier of every file-store fixture (42 of its tests reach it) while naming
  nothing the 2026-09-17 incident is about
  (`docs/seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md`).

  A program in which NOTHING declares `:seon.fn/destroys` is drift, not
  health: the checker would walk to nothing and report the tier clean, which
  is the absence-of-signal class this whole issue is about. So it throws."
  [rows]
  (let [owner-rows (filterv #(and (:seon.fn/sym %)
                                  (string? (:seon.fn/destroys %))
                                  (seq (:seon.fn/destroys %)))
                            rows)]
    (when (empty? owner-rows)
      (throw (ex-info (str "No analyzed declaration carries :seon.fn/destroys, "
                           "so tier selection cannot tell which tests delete a "
                           "filesystem path they did not create.")
                      {:seon.error/kind ::missing-destructive-owners})))
    owner-rows))

(defn- destructive-call-path
  "The shortest `:seon.fn/calls` path from one test down to a destructive
  owner, as the evidence a refusal hands its reader."
  [rows owner-symbols test-symbol]
  (let [callees (into {}
                      (map (fn [row]
                             [(or (:seon.fn/sym row) (:seon.test/sym row))
                              (into (set (:seon.fn/calls row)) (:seon.fn/references row))]))
                      rows)]
    (loop [frontier [[test-symbol]]
           seen #{test-symbol}]
      (when (seq frontier)
        (if-let [found (first (filter (comp owner-symbols peek) frontier))]
          found
          (let [next-frontier
                (for [path frontier
                      callee (get callees (peek path))
                      :when (not (seen callee))]
                  (conj path callee))]
            (recur (vec next-frontier)
                   (into seen (map peek) next-frontier))))))))

(defn- verify-platform-tier-carries-no-destructive-drill!
  "Refuse a platform tier containing a test that reaches a destructive owner.

  The platform tier runs FIRST on every `bin/test` invocation, before any
  other evidence exists, so a destructive fixture there deletes with nothing
  yet observed — on 2026-09-17 that cost the development store and the day's
  recorded results. The rule is enforced here, at the selection that admits
  the tier, so metadata drift cannot bypass it: a test reaching a destructive
  owner belongs to the bulk tier or `:seon.test/long`, under its own isolated
  root. The refusal names each test and its call path to the owner."
  [manifest platform-vars]
  (when (seq platform-vars)
    (let [rows (manifest-rows manifest)
          owner-rows (destructive-owner-rows rows)
          owner-symbols (set (map :seon.fn/sym owner-rows))
          destructive (tests-reaching-rows (:seon.fn.manifest/artifacts manifest) (set owner-rows))
          offenders (vec (for [test-var platform-vars
                               :let [test-symbol (var-symbol test-var)]
                               :when (destructive test-symbol)]
                           {:seon.test/sym test-symbol
                            ::destructive-path
                            (or (destructive-call-path rows owner-symbols
                                                       test-symbol)
                                [test-symbol ::reached-through-declared-subject])}))]
      (when (seq offenders)
        (throw
         (ex-info
          (str "The platform tier declares a destructive drill: "
               (str/join ", " (map :seon.test/sym offenders))
               ". The platform tier runs FIRST on every invocation, so a "
               "fixture that deletes a filesystem path runs there before any "
               "evidence exists; declare the test :seon.test/long or leave it "
               "to the bulk tier, under an isolated root.")
          {:seon.error/kind ::destructive-platform-test
           ::destructive-platform-tests offenders
           :seon.test.runner/destructive-platform-test true})))))
  nil)

(defn- expensive-fixture-tests
  "Derive fixture demand from program calls and declared request keywords.
  with-database's optional fresh-store branch is not an unconditional demand."
  [manifest]
  (let [rows (vec (mapcat :seon.fn.file/rows
                          (:seon.fn.manifest/artifacts manifest)))
        owners #{'seon.test-support/populate-published-root!
                 'seon.test-support/populate-published-operator-root!
                 'seon.test-support/with-fresh-database}
        owner-rows (filterv #(owners (:seon.fn/sym %)) rows)
        missing (set/difference owners (set (map :seon.fn/sym owner-rows)))]
    (when (seq missing)
      (throw (ex-info "Fixture selection cannot resolve its fixture owners."
                      {::missing-fixture-owners (vec (sort missing))})))
    (let [artifacts
          (mapv (fn [artifact]
                  (update artifact :seon.fn.file/rows
                          (fn [rows]
                            (mapv (fn [row]
                                    (if (= 'seon.test-support/with-database (:seon.fn/sym row))
                                      (update row :seon.fn/calls disj
                                              'seon.test-support/with-fresh-database)
                                      row)) rows))))
                (:seon.fn.manifest/artifacts manifest))
          rows (mapcat :seon.fn.file/rows artifacts)
          direct (tests-reaching-rows artifacts (set owner-rows))
          callers (mapv (fn [artifact]
                          (update artifact :seon.fn.file/rows
                                  #(mapv (fn [row]
                                           (cond-> row (:seon.fn/sym row)
                                             (assoc :seon.test/sym (:seon.fn/sym row)))) %)))
                        artifacts)
          branch-callers (tests-reaching-rows
                          callers
                          (set (filter #(= 'seon.test-support/with-database
                                           (:seon.fn/sym %))
                                       (mapcat :seon.fn.file/rows callers))))

          request-rows
          (filterv #(and (not= 'seon.test-support/with-database (:seon.fn/sym %))
                         (branch-callers (or (:seon.test/sym %) (:seon.fn/sym %)))
                         (some #{:seon.test-support/fresh-store?
                                 :seon.test-support/database-id}
                               (:seon.fn/keywords %))) rows)]
      (set/union direct
                 (tests-reaching-rows artifacts (set request-rows))
                 (set (keep :seon.test/sym request-rows))))))

(defn- verify-fixture-observations!
  [manifest selected-vars]
  (when (seq selected-vars)
    (let [expensive (expensive-fixture-tests manifest)
          offenders (->> selected-vars
                         (filter #(expensive (var-symbol %)))
                         (remove #(marker-reason % :seon.test/fixture-observation))
                         (map var-symbol)
                         distinct sort vec)]
      (when (seq offenders)
        (throw (ex-info
                (str "Selected tests reach an expensive fixture without a declared observation: "
                     (str/join ", " offenders) ".")
                {:seon.error/kind ::missing-fixture-observation
                 :seon.test/syms offenders
                 :seon.test/sym (first offenders)})))))
  nil)

(defn- run-selected-tests
  ([namespaces selected-vars] (run-selected-tests namespaces selected-vars nil))
  ([namespaces selected-vars ctx]
  (let [selected-by-namespace (group-by (comp :ns meta) selected-vars)]
    (binding [test/*report-counters* (ref test/*initial-report-counters*)]
      (doseq [namespace-name namespaces
              [namespace-object namespace-vars] selected-by-namespace
              :when (= (str namespace-name) (str namespace-object))
              :let [host? (instance? clojure.lang.Namespace namespace-object)
                    bindings (if host? (ns-interns namespace-object)
                                 (get (sci/namespace-state ctx) namespace-name))
                    hook (get bindings 'test-ns-hook)]
              :when (seq namespace-vars)]
        (when host? (test/do-report {:type :begin-test-ns :ns namespace-object}))
        (if hook
          (if (= (set namespace-vars)
                 (set (filter (comp :test meta) (vals bindings))))
            (@hook)
            (throw
             (ex-info
              "A namespace test hook requires its complete admitted selection."
              {:seon.error/kind :seon.test/namespace-hook-requires-complete-selection
               :seon.ns/name namespace-name :seon.test.runner/long-test-ns-hook namespace-name})))
          (test/test-vars namespace-vars))
        (when host? (test/do-report {:type :end-test-ns :ns namespace-object})))
      @test/*report-counters*))))

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
        options (report-options)
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

(defonce ^:private resolve-admitted-test
  (delay (requiring-resolve 'seon.test/resolve-test)))

(defonce ^:private run-interpreted-tests
  (delay (requiring-resolve 'seon.sci.eval/run-tests)))

(defn- run-resolved-tests!
  "Run host Vars directly and SCI Vars under their context's arm.

  A cold worker resolves core tests to JVM Vars. Arming the canonical fixture
  ctx around those host bodies makes the worker's infrastructure arm look like
  their evaluation: a test that creates or evaluates an independent ctx then
  meets a foreign arm for the whole duration of its own body. SCI Vars do need
  their interpreter arm; a task mixing the two kinds has no honest single
  fixture boundary and is refused."
  [resolution task test-vars]
  (let [host-vars (filterv #(instance? clojure.lang.Var %) test-vars)
        custody (or (:seon.db/custody-request resolution) {})
        request (merge (select-keys resolution [:seon.sci.eval/ctx])
                       custody
                       {:seon.test/vars test-vars
                        :seon.sci.eval/time-limit-ms
                        (* 1000 (bounds/exchange-seconds
                                 (or (::task-long-ms task) 0) 0))})]
    (cond
      (= (count host-vars) (count test-vars))
      ;; `eval/run-tests` handed the complete request to `run-vars!`: the ctx
      ;; is fixture input even though host Vars must not run under its arm.
      ;; Keep that value carriage when the host path bypasses the SCI owner.
      (run-vars! test-vars request)

      (empty? host-vars)
      (@run-interpreted-tests request)

      :else
      {:seon.error/kind ::mixed-host-and-sci-task
       :seon.error/message
       "One worker task resolved both host and SCI test Vars; their fixture and arm boundaries cannot be shared."
       ::task-symbols (::task-symbols task)})))

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
  "Observe an acquired SCI base without forcing an unrealized or failed delay.
  A memoized acquisition failure is an explicit unavailable observation,
  not test setup and not an acquired context."
  [base]
  (when (and base (realized? base))
    (try
      (when-let [env (some-> @base :seon.sci.eval/ctx :env)]
        (into {}
              (map (fn [[namespace-name bindings]]
                     [namespace-name (count bindings)]))
              (:namespaces @env)))
      (catch Exception failure
        {::fixture-base-unavailable true
         :seon.error/kind ::fixture-base-unavailable
         :seon.error/message (or (ex-message failure)
                                 (.getName (class failure)))}))))

(defn live-cluster-schema-states
  "Each running cluster's projection state and the environment it holds now.

  The pair is what a restore needs: the atom to write, and the environment
  value carrying the CONNECTION whose facts decide what belongs in it. The
  environment's key set is evidence about what the run entered with, never
  the thing a restore puts back — that is derived from the connection.
  Nothing is remembered between calls."
  {:malli/schema [:=> [:cat] :map]}
  []
  (into {}
        (keep (fn [[cluster-name instance]]
                (when-let [state (get-in instance
                                         [:seon.sci.eval/ctx env/state-carrier])]
                  [cluster-name [state @state]])))
        (some-> (resolve-loaded 'seon.operator.runtime/running-instances)
                var-get deref)))

(defn- projection-form-keys
  "The declaration keys one projection value holds."
  [projection]
  (set (keys (:seon.schema.projection/forms projection))))

(defn restore-live-cluster-schema!
  "Advance each live cluster's schema projection to the one ITS OWN FACTS
  declare, and name every key on which the run's exit state disagreed.

  A live cluster's declarations are database facts; its projection is a
  compiled view of them. So the question after an in-process run is never
  `what did this projection hold before?` — it is `what do the cluster's
  committed facts say now?`. `projection-from-database` at the connection's
  current basis answers exactly that, and `env/advance-projection!` is the
  same seam adoption and evaluation advance through: ONE restore path.

  Putting the ENTERING SNAPSHOT back was a mirror acting against the writer.
  On 2026-09-17 a probe turn deliberately retracted four declarations from
  `default` during a run; the snapshot restore reasserted them, and the
  cluster's projection disagreed with its own committed facts until an
  explicit advance repaired it
  (`docs/seon/issues/the-drift-restore-undoes-a-committed-schema-retraction.md`).

  The snapshot is still taken, and still matters — as EVIDENCE for naming,
  not as the thing restored. Comparing the run's exit key set with the
  derived one classifies every difference by whether the entering set held
  the key:

  - present at exit, absent from the facts, not entering: the run registered
    it and committed nothing — `::drift-added`, restored away;
  - present at exit, absent from the facts, entering: a committed transaction
    retracted it — `::committed-removed`, and it STAYS retracted;
  - in the facts, absent at exit, entering: the run dropped it in memory —
    `::drift-removed`, restored back;
  - in the facts, absent at exit, not entering: a committed transaction added
    it — `::committed-added`.

  A cluster whose snapshot carries no connection has no authority to derive
  from; that is reported as `::schema-authority-unavailable` — the typed
  unknown, never silence (AGENTS §2.4). A run that touched no declarations
  reports nothing at all."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [before]
  (into
   []
   (keep
    (fn [[cluster-name [state entering-environment]]]
      (let [connection (:seon.db/connection entering-environment)
            exit-projection (:seon.schema/projection @state)
            database (when connection (db/db connection))]
        (if (or (nil? connection) (:seon.error/kind database))
          {:seon.cluster/name cluster-name
           ::schema-authority-unavailable
           (if connection
             (str "The cluster's declaration facts could not be read: "
                  (:seon.error/message database))
             "The snapshot carries no connection, so the cluster's declaration facts could not be read.")}
          ;; The ENTERING projection is the reusable value, never the exit
          ;; one: reuse is decided by the fingerprint the value CARRIES, and
          ;; a projection a run edited in memory still carries the
          ;; fingerprint of the rows it was built from. Reusing it would let
          ;; the run's own edit answer the question the facts must answer.
          (let [derived (schema/projection-from-database
                         database (:seon.schema/projection entering-environment))
                entering-keys (projection-form-keys
                               (:seon.schema/projection entering-environment))
                exit-keys (projection-form-keys exit-projection)
                derived-keys (projection-form-keys derived)]
            (when-not (identical? derived exit-projection)
              (env/advance-projection! state (db/basis-t database) derived))
            (let [uncommitted (set/difference exit-keys derived-keys)
                  absent (set/difference derived-keys exit-keys)
                  named (fn [declaration-keys]
                          (vec (sort (map str declaration-keys))))
                  drift-added (set/difference uncommitted entering-keys)
                  committed-removed (set/intersection uncommitted entering-keys)
                  drift-removed (set/intersection absent entering-keys)
                  committed-added (set/difference absent entering-keys)
                  row (cond-> {:seon.cluster/name cluster-name}
                        (seq drift-added)
                        (assoc ::drift-added (named drift-added))
                        (seq drift-removed)
                        (assoc ::drift-removed (named drift-removed))
                        (seq committed-added)
                        (assoc ::committed-added (named committed-added))
                        (seq committed-removed)
                        (assoc ::committed-removed (named committed-removed)))]
              (when (next row) row))))))
    before)))

(defn schema-restore-drift
  "The restore rows naming a run's OWN uncommitted change, and only those.

  A committed addition or retraction is the writer doing its job during a
  run; a key the run registered or dropped in memory is the run failing to
  own nothing global. Only the second is a test error, so the caller that
  turns rows into a verdict asks here rather than reading the row keys."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [rows]
  (filterv #(or (seq (::drift-added %)) (seq (::drift-removed %))) rows))

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
               (instrument/instrumented))
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
             #{})
         ;; A LIVE CLUSTER'S SCHEMA REGISTRY IS NOT A MEMBER HERE.
         ;; Its authority is the cluster's own declaration facts, so a
         ;; before/after key-set diff cannot tell a run's leak from a
         ;; committed retraction — it reported the latter as drift.
         ;; `restore-live-cluster-schema!` derives that answer from the
         ;; facts and is the ONE owner of it.
         }]
    (if-let [sizes (sci-base-namespace-sizes
                     (some-> (resolve-loaded 'seon.test-support/database-base) var-get))]
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
  [task resolution]
  (let [options (report-options)
        output (StringWriter.)
        started-at (Instant/now)
        started-nanos (System/nanoTime)]
    (try
      (let [test-vars (mapv #(@resolve-admitted-test
                              (assoc resolution :seon.test/identity (symbol %)))
                            (::task-symbols task))
            _ (when-let [failure (first (filter :seon.error/kind test-vars))]
                (throw (ex-info (:seon.error/message failure) failure)))
            results
            (binding [*out* output
                      *err* output
                      test/*test-out* output]
              (run-resolved-tests! resolution task test-vars))
            _ (when (:seon.error/kind results)
                (throw (ex-info (:seon.error/message results) results)))
            summary {::test-count (count results)
                     ::pass-count (reduce + 0 (map :seon.test/pass-count results))
                     ::fail-count (reduce + 0 (map :seon.test/fail-count results))
                     ::error-count (reduce + 0 (map :seon.test/error-count results))}]
        (assoc task
               ::task-started-at (str started-at)
               ::task-ended-at (str (Instant/now))
               ::task-elapsed-ms
               (quot (- (System/nanoTime) started-nanos) 1000000)
               ::task-summary summary
               ::task-results results
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
  (let [forms (schema.edn/packaged-forms)
        _ (load-declared-predicate-owners! forms)
        projection (schema/declaration-projection forms)]
    (binding [*out* *err*]
      (println "bin/test: PACKAGED TEST PROJECTION ACQUIRED"
               "at=" (str (Instant/now))
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

(def ^:private arm-contracts! #'test.arm/arm-contracts!)

(defn- initialize-contracts!
  "Load selected tests and acquire the one arming value for workers and test-fast."
  [role namespaces projection]
  (test.arm/initialize-contracts! role namespaces projection))

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
    (let [live (count (instrument/instrumented))]
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

(defn- bounded-worker-task!
  "Run one task inside the worker's own bound, before the coordinator's bound.

  Host Vars deliberately do not borrow the canonical SCI ctx's arm
  (`run-resolved-tests!`). That arm had been the host task's only aggregate
  deadline, so removing it left the worker running a host body with no bound
  of its own: the coordinator's exchange bound expired first, retired the
  worker, and every remaining task on that tier was reported failed unrun
  with no terminal event to attribute it to. The bound belongs at the seam
  that admits the work (AGENTS §2.3), so the worker command owns it.

  The bounds nest: this one is `bounds/exchange-seconds` WITHOUT the measured
  fixture priming the coordinator's `task-exchange-bound-seconds` adds, so it
  fires strictly first and leaves the worker time to publish an attributed
  `:task-complete`. The coordinator remains the process-level backstop for
  code that ignores interruption. `executor` is the worker's ONE long-lived
  execution thread: pooled-thread `ThreadLocal` carriage — the SCI arm above
  all — stays observable exactly as it was when `run-task!` ran in place."
  [task resolution ^java.util.concurrent.ExecutorService executor]
  (let [started-at (Instant/now)
        started-nanos (System/nanoTime)
        bound-seconds (bounds/exchange-seconds (or (::task-long-ms task) 0) 0)
        ^java.util.concurrent.Callable task-callable
        (on-caller-loader #(run-task! task resolution))
        execution (.submit executor task-callable)]
    (try
      (.get ^java.util.concurrent.Future execution bound-seconds TimeUnit/SECONDS)
      ;; The execution thread is an implementation detail of this bound, never
      ;; a new failure class: a task that throws reaches the worker's own
      ;; handler exactly as it did when `run-task!` was called in place.
      (catch ExecutionException failure
        (throw (or (.getCause failure) failure)))
      (catch TimeoutException _
        (.cancel ^java.util.concurrent.Future execution true)
        (let [test-symbols (mapv str (::task-symbols task))
              elapsed-ms (quot (- (System/nanoTime) started-nanos) 1000000)
              message (str "Worker task reached its " bound-seconds
                           "s execution bound before returning.")]
          (assoc task
                 ::task-started-at (str started-at)
                 ::task-ended-at (str (Instant/now))
                 ::task-elapsed-ms elapsed-ms
                 ::task-summary {::test-count (count test-symbols)
                                 ::pass-count 0
                                 ::fail-count 0
                                 ::error-count (count test-symbols)}
                 ::task-results
                 (mapv (fn [test-symbol]
                         #:seon.test{:sym test-symbol
                                     :pass-count 0
                                     :fail-count 0
                                     :error-count 1
                                     :failing-assertions
                                     [(id/id [test-symbol ::worker-task-bound] 64)]
                                     :failure-message message})
                       test-symbols)
                 ::task-output (str message "\n")
                 ::worker-task-bound true))))))

(defn- serve-worker-commands!
  "Read and execute worker commands serially until explicitly stopped."
  [worker-id ^BufferedReader reader ^PrintWriter writer arming]
  (loop []
    (when-let [line (.readLine reader)]
      (let [command (edn/read-string line)]
        (case (::worker-command command)
          :initialize
          (let [namespaces (mapv symbol (::worker-namespaces command))
                arming (merge arming (initialize-contracts! worker-id namespaces (::projection arming)))]
              (write-protocol! writer
                               {::worker-event :initialized
                                ::worker-id worker-id
                                ::exchange-id (::exchange-id command)
                                ::worker-namespace-count (count namespaces)
                                ::worker-instrumented
                                (::instrumented arming)})
              (schema/call-with-projection
               (::projection arming)
               #(serve-worker-commands!
                 worker-id reader writer arming)))

          :run
          (do
            (write-protocol! writer
                             {::worker-event :re-arming
                              ::worker-id worker-id
                              ::exchange-id (::exchange-id command)})
            (reassert-contracts! arming worker-id)
            (write-protocol! writer
                             {::worker-event :armed
                              ::worker-id worker-id
                              ::exchange-id (::exchange-id command)})
            ;; THE WORKER MEASURES WHAT A TASK LEAVES BEHIND. A pooled worker
            ;; runs many tests per JVM, and the reds that only appear under
            ;; the whole gate are tests asserting an EARLIER task's leftovers.
            ;; Nothing declares which state is shared, so the seam that admits
            ;; the work derives it either side of the task and reports the
            ;; difference as that task's own fact.
            (let [before (ambient-snapshot)
                  result (bounded-worker-task! (::worker-task command)
                                               (::resolution arming)
                                               (::task-executor arming))
                  drift (ambient-drift before (ambient-snapshot))]
              (write-protocol! writer
                               (cond-> (assoc result
                                              ::worker-event :task-complete
                                              ::worker-id worker-id
                                              ::exchange-id
                                              (::exchange-id command)
                                              ;; A terminal exchange never
                                              ;; prints an empty elapsed field,
                                              ;; even if an injected task owner
                                              ;; omitted its own measurement.
                                              ::task-elapsed-ms
                                              (or (::task-elapsed-ms result) 0))
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
  "Prime the canonical fixture before readiness, then serve commands until stopped."
  [worker-id ^BufferedReader reader ^PrintWriter writer]
  (let [projection (packaged-test-projection worker-id)
        ;; SCI copies core roots; arm before the canonical fixture acquires them.
        _ (initialize-contracts! worker-id [] projection)
        started (System/nanoTime)
        base (schema/call-with-projection
              projection
              ;; `seon.test-support` lives under `test/`: a deliberate late
              ;; dependency of the worker, never a load-cycle dodge.
              #(deref @(requiring-resolve 'seon.test-support/database-base)))]
    (when (:seon.error/kind base)
      (throw (ex-info "A test worker could not prepare its canonical fixture base."
                      base)))
    (write-protocol! writer {::worker-event :ready
                            ::worker-id worker-id
                            ::fixture-preparation-ms
                            (quot (- (System/nanoTime) started) 1000000)
                            ::exchange-id (str worker-id "/readiness")})
    (let [connection (:seon.test-support/connection base)
          database (db/db connection)
          task-executor
          (Executors/newSingleThreadExecutor
           (reify ThreadFactory
             (newThread [_ runnable]
               (doto (Thread. runnable (str "seon-test-worker-" worker-id))
                 (.setDaemon true)))))]
      (try
        (serve-worker-commands!
         worker-id reader writer
         {::projection projection
          ::task-executor task-executor
          ::resolution {:seon.db/db database :seon.db/connection connection
                        :seon.sci.eval/ctx (:seon.sci.eval/ctx base)
                        :seon.schema/projection (schema/projection-from-database database)
                        :seon.test/class-loader (clojure.lang.RT/baseLoader)}})
        (finally
          (.shutdownNow task-executor))))))

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

(defn- program-fact
  [pulled]
  (let [row (program/canonical-row (dissoc pulled :db/id))
        [attribute] (program/row-identity row)]
    (when (and attribute
               (get row (:seon.program/source-attribute (program/shape attribute))))
      (walk/postwalk
       (fn [value]
         (cond
           (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) value)
           (set? value) (vec (sort-by pr-str value))
           :else value))
       row))))

(def ^:private reach-attributes
 [:seon.fn/sym :seon.fn/source :seon.fn/spec :seon.fn/calls :seon.fn/references :seon.fn/keywords
  :seon.test/sym :seon.test/source :seon.test/subject
  :seon.schema/key :seon.schema/form])
(defn- reach-keywords [form]
 (into #{} (filter qualified-keyword?) (tree-seq coll? seq form)))
(defn- reach-canonical [value]
 (walk/postwalk (fn [v] (cond
  (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) v)
  (set? v) (into (sorted-set-by #(compare (pr-str %1) (pr-str %2))) v)
  :else v)) value))
(defn- reach-row [row]
 (let [sym (or (:seon.test/sym row) (:seon.fn/sym row))
       schema-key (:seon.schema/key row)
       spec (some-> (:seon.fn/spec row) edn/read-string)
       form (some-> (:seon.schema/form row) edn/read-string)]
  (cond-> row
   sym (assoc ::reach-symbol sym
              ::reach-leaf (id/digest 64 [sym (or (:seon.test/source row) (:seon.fn/source row)) (:seon.fn/spec row)])
              ::reach-keys (into (set (:seon.fn/keywords row)) (reach-keywords spec)))
   schema-key (assoc ::reach-leaf (id/digest 64 [schema-key (reach-canonical form)])
                     ::reach-keys (reach-keywords form)))))
(defn- reach-schema-keys [rows schemas seed]
  (loop [pending [seed] seen #{}]
    (if-let [k (peek pending)]
      (if (seen k)
        (recur (pop pending) seen)
        (recur (into (pop pending) (get-in rows [(get schemas k) ::reach-keys]))
               (conj seen k)))
      seen)))

(defn- reach-refresh [database previous]
 (let [basis (db/basis-t database)
       ids (if previous
             (db/q '[:find [?e ...] :in $ [?a ...] :where [?e ?a]]
                   (db/since (db/history database) (::reach-basis previous)) reach-attributes)
             (db/q '[:find [?e ...] :in $ [?a ...] :where [?e ?a]]
                   database [:seon.fn/sym :seon.test/sym :seon.schema/key]))
       _ (when (:seon.error/kind ids) (throw (ex-info "Reach identities unavailable." ids)))
       pulled (if (seq ids)
                (db/pull-many database
                 '[:db/id :seon.fn/sym :seon.fn/source :seon.fn/spec :seon.fn/keywords
                   (limit :seon.fn/calls nil) (limit :seon.fn/references nil) :seon.test/sym :seon.test/source
                   :seon.test/subject
                   :seon.schema/key :seon.schema/form] ids) [])
       _ (when (:seon.error/kind pulled) (throw (ex-info "Reach rows unavailable." pulled)))
       old-rows (::reach-rows previous {})
       pulled (mapv (fn [e r] (assoc (or r {}) :db/id e)) ids pulled)
       changed (filterv #(not= (dissoc (get old-rows (:db/id %)) ::reach-symbol ::reach-leaf ::reach-keys) %) pulled)
       rows (reduce (fn [rs r] (assoc rs (:db/id r) (reach-row r))) old-rows changed)
       schemas (if (seq changed)
                 (into {} (keep (fn [[e row]] (when-let [k (:seon.schema/key row)] [k e]))) rows)
                 (::reach-schemas previous {}))
       changed-schema-keys (into #{} (keep :seon.schema/key) changed)
       schema-closures (if (and previous (empty? changed-schema-keys))
                         (::reach-schema-closures previous)
                         (reduce-kv
                         (fn [closures k _]
                           (if (and (get closures k)
                                    (not (some (get closures k) changed-schema-keys)))
                             closures
                             (assoc closures k (reach-schema-keys rows schemas k))))
                         (::reach-schema-closures previous {}) schemas))
       tokens (into (set (map :db/id changed))
                    (mapcat (fn [r] (for [v [r (get old-rows (:db/id r))]
                                         :let [s (or (:seon.test/sym v) (:seon.fn/sym v))
                                               k (:seon.schema/key v)]
                                         token (cond-> [] s (conj [::reach-symbol s]) k (conj [::reach-schema k]))]
                                     token))) changed)
       kept (if (seq tokens)
              (into {} (remove (fn [[_ entry]] (some (::reach-dependencies entry) tokens)))
                    (::reach-digests previous {}))
              (::reach-digests previous {}))]
  (assoc (or previous {})
    ::reach-basis basis ::reach-rows rows
    ::reach-symbols (if (seq changed) (into {} (keep (fn [[e r]] (when-let [s (::reach-symbol r)] [s e]))) rows) (::reach-symbols previous {}))
    ::reach-schemas schemas ::reach-schema-closures schema-closures
    ::reach-digests kept
    ::reach-updated (count changed) ::reach-invalidated (- (count (::reach-digests previous)) (count kept)))))
(defn- reach-entry [index test-symbol]
  (let [rows (::reach-rows index)
        symbols (::reach-symbols index)
        schemas (::reach-schemas index)
        start (get symbols test-symbol)
        names (loop [pending (if start [test-symbol] []) seen #{}]
                (if-let [target (peek pending)]
                  (if (seen target)
                    (recur (pop pending) seen)
                    (let [row (get rows (get symbols target))]
                      (recur (into (pop pending)
                                   (concat (:seon.fn/calls row)
                                           (:seon.fn/references row)
                                           (when-let [subject (:seon.test/subject row)] [subject])))
                             (conj seen target))))
                  seen))
        entities (into #{} (keep symbols) names)
        keyword-seeds (reduce into #{} (map #(get-in rows [% ::reach-keys]) entities))
        schema-keys (reduce into #{}
                            (map #(get (::reach-schema-closures index) % #{%}) keyword-seeds))
        node-parts (mapv (fn [name] [name (get-in rows [(get symbols name) ::reach-leaf]
                                                  :seon.error/unknown)]) (sort names))
        schema-parts (mapv (fn [key] [key (get-in rows [(get schemas key) ::reach-leaf])])
                           (sort schema-keys))]
    {::reach-digest (id/digest 64 [test-symbol node-parts schema-parts])
     ::reach-refs (when start
                    (into #{} (remove #(= % test-symbol)) names))
     ::reach-dependencies (into entities
                                (concat (map #(vector ::reach-symbol %) names)
                                        (map #(vector ::reach-schema %) schema-keys)))
     ::reach-function-count (count names)}))
(defn- reach-cache [database]
 (or (:seon.sci.eval/projection-state (meta database))
     (when-let [projection (db/carried-projection database)]
      (schema/projection-cache-value projection
       [::reach-cache (:config database)] #(atom nil)))))
(defn- reach-entries
 "Derive selected reach digests, incrementally on the database's carried cache.
 Result-only transactions invalidate nothing. A cache retains only one basis;
 older or different branch values derive independently and never replace it."
 [database test-symbols]
 (try
 (let [holder (reach-cache database)
       configuration (:config database)
       value-identity (db/committed-value-identity database)
       derive-index (fn [previous]
                (let [index (if (= (db/basis-t database) (::reach-basis previous))
                              previous (reach-refresh database previous))
                      missing (remove #(get-in index [::reach-digests % ::reach-refs]) test-symbols)
                      index (reduce (fn [i s] (assoc-in i [::reach-digests s] (reach-entry i s))) index missing)]
                 (assoc index ::reach-config configuration ::reach-value-identity value-identity ::reach-computed (count missing))))]
  (if holder
   (locking holder
    (let [previous (::reach-index (meta holder))
          usable (and value-identity (= configuration (::reach-config previous))
                      (or (< (::reach-basis previous 0) (db/basis-t database))
                          (and value-identity (= value-identity (::reach-value-identity previous)))))
          index (derive-index (when usable previous))]
     (when (and value-identity (or usable (nil? previous)))
      (alter-meta! holder assoc ::reach-index index))
     (select-keys (::reach-digests index) test-symbols)))
   (let [index (derive-index nil)]
    (select-keys (::reach-digests index) test-symbols))))
 (catch Exception failure
  {:seon.error/kind :seon.test/unknown :seon.test/unknown "reach digest"
   :seon.error/message (str "Reach digest unavailable: " (ex-message failure))})))

(defn reach-digests
  "Derive equality keys from the tested database's incremental reach index."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]]
                  [:or :seon.test/reach-digests :seon.error/value]]}
  [database test-symbols]
  (let [entries (reach-entries database test-symbols)]
    (if (:seon.error/kind entries) entries
        (into {} (map (fn [[s entry]] [s (::reach-digest entry)])) entries))))

(defn reach-memberships
  "The tested closure's function names, including unresolved targets."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]]
                  [:or :seon.test/reaches :seon.error/value]]}
  [database test-symbols]
  (let [entries (reach-entries database test-symbols)]
    (if (:seon.error/kind entries) entries
        (into {} (keep (fn [[s entry]] (when (set? (::reach-refs entry))
                                       [s (::reach-refs entry)]))) entries))))

(defn program-digest
  "Identify the tested program from its source seal and current program facts.
  An unchanged publication keeps its exact snapshot digest. Admitted changes
  extend that identity with canonical program facts; result-only writes do not.
  Only program rows touched since the seal need comparison."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :seon.test.run/program-digest :seon.error/value]]}
  [database]
  (try
   (let [seals (db/q '[:find ?digest ?t
                     :where [_ :seon.source/digest ?digest ?t]] database)
        _ (when (:seon.error/kind seals)
            (throw (ex-info "Cannot read the tested source identity." seals)))
        _ (when (> (count seals) 1)
            (throw (ex-info "The tested program has multiple source seals." {})))
        [digest basis] (first seals)
        _ (when-not digest
            (throw (ex-info "The tested program has no source snapshot identity." {})))
        before (db/as-of database basis)
        changed (db/since (db/history database) basis)
        entities (db/q '[:find [?entity ...]
                         :in $ $changed [?identity ...]
                         :where [$changed ?entity]
                                [?entity ?identity]]
                       database changed program/identity-attributes)
        _ (when (:seon.error/kind entities)
            (throw (ex-info "Cannot identify changed program rows." entities)))
        old-rows (db/pull-many before '[*] entities)
        current-rows (db/pull-many database '[*] entities)
        _ (doseq [rows [old-rows current-rows]]
            (when (:seon.error/kind rows)
              (throw (ex-info "Cannot read tested program rows." rows))))
        differences
        (into []
              (keep (fn [[old-row current-row]]
                      (let [old (program-fact old-row)
                            current (program-fact current-row)]
                        (when (not= old current)
                          [(program/row-identity (or current old)) current]))))
              (map vector old-rows current-rows))]
    (if (empty? differences) digest
        (id/digest 64 [digest (vec (sort-by pr-str differences))])))
   (catch Exception failure
     {:seon.error/kind :seon.test.run/unavailable
      :seon.test.run/unavailable true
      :seon.error/message (str "Test provenance unavailable: " (ex-message failure))})))

(defn provenance
  "Capture immutable test custody before execution.
  Git is optional for an
  agent's database program, which need not have a corresponding Git commit."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :seon.test.run/provenance :seon.error/value]]}
  [database]
  (let [digest (program-digest database)]
    (if (:seon.error/kind digest) digest
        {:seon.test.run/id (id/id)
   :seon.test.run/at (java.util.Date.)
   :seon.test.run/program-digest digest
   :seon.test.run/basis-t (db/basis-t database)
   :seon.test.run/branch (get-in database [:config :branch])})))

(defn- admission-refusal! [kind run-id expected offending]
  (let [failure
        (error/diagnostic
         {:seon.error/kind kind
          :seon.error/message "Test run admission refused inconsistent evidence."
          :seon.error/diagnostic-layer :test
          :seon.error/diagnostic-operation :seon.test.runner/admit-run
          :seon.error/diagnostic-member run-id
          :seon.error/diagnostic-expected expected
          :seon.error/diagnostic-offending offending
          :seon.error/diagnostic-cause kind
          :seon.error/diagnostic-evidence {:seon.test.run/id run-id}})]
    (throw (ex-info (:seon.error/message failure) failure))))

(defn- admission-members [database run-id]
  ;; Query refs rather than pulling the parent's cardinality-many collection:
  ;; Datahike's default pull limit must not shorten an execution obligation.
  (let [ids (db/q '[:find [?member ...] :in $ ?run [?attribute ...]
                    :where [?run ?attribute ?member]]
                  database run-id
                  [:seon.test.run/members :seon.test.run/covered-by])]
    (when (:seon.error/kind ids)
      (throw (ex-info (:seon.error/message ids) ids)))
    (mapv #(let [row (db/pull database
                             [:db/id :seon.test.member/symbol :seon.test.member/reasons] %)]
             (when (or (:seon.error/kind row)
                       (not (:seon.test.member/symbol row))
                       (not (seq (:seon.test.member/reasons row))))
               (admission-refusal! :seon.test/population-unknown run-id
                                   :admitted-member (or row :seon.error/unknown)))
             row)
          ids)))

(defn- admission-scope [row]
  (cond-> (-> (select-keys row [:seon.test.run/cluster :seon.test.run/program-digest
                       :seon.test.run/input-digest :seon.test.run/branch
                       :seon.test.run/tested-branch :seon.test.run/change-basis-t
                       :seon.test.run/policy :seon.test.run/include-long?])
      (assoc :seon.test.run/namespaces (set (:seon.test.run/namespaces row))
             :seon.test.run/identities (set (:seon.test.run/identities row))))
    (= :named (:seon.test.run/policy row))
    (assoc :seon.test.run/basis-t (:seon.test.run/basis-t row))))

(defn- admission-member-values [members]
  (into #{} (map #(-> (select-keys % [:seon.test.member/symbol
                                     :seon.test.member/reasons])
                      (update :seon.test.member/reasons set))) members))

(defn admit-run
  "Reserve a supplied selection at the writer, before any test executes.

  Invoke with [:db.fn/call seon.test.runner/admit-run request]. The request
  carries the publisher's input digest and immutable tested provenance;
  selection remains its caller's responsibility. This function does not
  select tests or execute bodies. It checks the current program and custody,
  then reserves only members not already covered by matching admitted runs.
  Concurrent requests see earlier reservations in the transaction database.
  An identical replay emits no evidence datoms; a changed replay refuses.

  This entry admits the primary host's own branch. Isolated snapshot custody
  requires its separate immutable publication handoff before admission."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/admission]
                  :seon.store/transaction-data]}
  [database {run :seon.test.run/provenance
             cluster :seon.test.run/cluster
             members :seon.test.run/members :as request}]
  (let [run-id (:seon.test.run/id run)
        cluster-row (db/pull database [:db/id :seon.cluster/name] cluster)
        cluster-id (:db/id cluster-row)
        branch (get-in database [:config :branch])
        digest (program-digest database)
        row (-> (merge (select-keys request
                                   [:seon.test.run/cluster :seon.test.run/input-digest
                                    :seon.test.run/policy :seon.test.run/include-long?
                                    :seon.test.run/deadline
                                    :seon.test.run/change-basis-t :seon.test.run/namespaces
                                    :seon.test.run/identities])
                       (select-keys run
                                    [:seon.test.run/id :seon.test.run/at :seon.test.run/git-sha
                                     :seon.test.run/program-digest :seon.test.run/basis-t
                                     :seon.test.run/branch :seon.test.run/tested-branch]))
                (assoc :seon.test.run/cluster cluster-id))
        selector (into [:db/id :seon.test.run/selection-tx
                        [:seon.test.run/namespaces :limit nil]
                        [:seon.test.run/identities :limit nil]]
                       (keys (dissoc row :seon.test.run/members
                                     :seon.test.run/namespaces :seon.test.run/identities)))
        previous (db/pull database selector [:seon.test.run/id run-id])]
    (doseq [read-result [cluster-row previous]]
      (when (:seon.error/kind read-result)
        (throw (ex-info (:seon.error/message read-result) read-result))))
    (when-not (:seon.cluster/name cluster-row)
      (admission-refusal! :seon.test/cluster-unavailable run-id
                          :explicit-authority-cluster cluster))
    (when (or (not= branch (:seon.test.run/branch run))
              (:seon.test.run/tested-branch run))
      (admission-refusal! :seon.test/cluster-mismatch run-id branch
                          (select-keys run [:seon.test.run/branch
                                            :seon.test.run/tested-branch])))
    (when (or (:seon.error/kind digest)
              (not= digest (:seon.test.run/program-digest run)))
      (admission-refusal! :seon.test/program-mismatch run-id digest
                          (:seon.test.run/program-digest run)))
    (when (or (> (:seon.test.run/basis-t run) (db/basis-t database))
              (> (get request :seon.test.run/change-basis-t 0)
                 (:seon.test.run/basis-t run)))
      (admission-refusal! :seon.test/invalid-basis run-id
                          (db/basis-t database)
                          (select-keys row [:seon.test.run/basis-t
                                            :seon.test.run/change-basis-t])))
    (when (not= (count members) (count (set (map :seon.test.member/symbol members))))
      (admission-refusal! :seon.test.run/immutable run-id
                          :one-membership-per-symbol members))
    (if (:db/id previous)
      (let [prior (-> (dissoc previous :db/id :seon.test.run/selection-tx)
                      (assoc :seon.test.run/cluster
                             (get-in previous [:seon.test.run/cluster :db/id])))
            normalize #(-> %
                           (dissoc :seon.test.run/namespaces :seon.test.run/identities)
                           (merge (select-keys (admission-scope %)
                                              [:seon.test.run/namespaces
                                               :seon.test.run/identities])))]
        (when (or (not (:seon.test.run/selection-tx previous))
                  (not= (normalize row) (normalize prior))
                  (not= (admission-member-values members)
                        (admission-member-values
                         (admission-members database (:db/id previous)))))
          (admission-refusal! :seon.test.run/immutable run-id row prior))
        [])
      (let [runs (db/q '[:find [?run ...] :in $ ?cluster ?digest ?inputs
                         :where [?run :seon.test.run/cluster ?cluster]
                                [?run :seon.test.run/program-digest ?digest]
                                [?run :seon.test.run/input-digest ?inputs]
                                [?run :seon.test.run/selection-tx]]
                       database cluster-id digest (:seon.test.run/input-digest row))
            _ (when (:seon.error/kind runs)
                (throw (ex-info (:seon.error/message runs) runs)))
            existing
            (into {}
                  (mapcat (fn [run-eid]
                            (let [candidate (db/pull database selector run-eid)
                                  _ (when (:seon.error/kind candidate)
                                      (throw (ex-info (:seon.error/message candidate) candidate)))
                                  candidate (assoc candidate :seon.test.run/cluster
                                                   (get-in candidate [:seon.test.run/cluster :db/id]))]
                              (when (= (admission-scope row) (admission-scope candidate))
                                (map (juxt :seon.test.member/symbol identity)
                                     (admission-members database run-eid))))))
                  (sort > runs))
            covered (keep #(get existing (:seon.test.member/symbol %)) members)
            reserved (remove #(get existing (:seon.test.member/symbol %)) members)]
        [(cond-> (assoc row :seon.test.run/selection-tx "datomic.tx")
           (seq reserved) (assoc :seon.test.run/members (vec reserved))
           (seq covered) (assoc :seon.test.run/covered-by (set (map :db/id covered))))]))))

(defn- execution-refusal! [operation run-id kind expected observed]
  (let [failure (error/diagnostic
                 {:seon.error/kind kind
                  :seon.error/message "The test execution evidence does not authorize this transition."
                  :seon.error/diagnostic-layer :test-execution
                  :seon.error/diagnostic-operation operation
                  :seon.error/diagnostic-member run-id
                  :seon.error/diagnostic-expected expected
                  :seon.error/diagnostic-offending observed
                  :seon.error/diagnostic-cause kind
                  :seon.error/diagnostic-evidence {:seon.test.run/id run-id}})]
    (throw (ex-info (:seon.error/message failure) failure))))

(defn- execution-read [value]
  (when (:seon.error/kind value)
    (throw (ex-info (:seon.error/message value) value)))
  value)

(defn- execution-members [database run-id]
  (let [run (execution-read
             (db/pull database [:db/id :seon.test.run/selection-tx]
                      [:seon.test.run/id run-id]))]
    (when-not (:seon.test.run/selection-tx run)
      (execution-refusal! 'seon.test.runner/claim-member run-id
                          :seon.test/population-unknown :admitted-selection
                          (or run :absent)))
    (let [members-at
          (fn [value]
            (execution-read
             (db/q '[:find [?member ...] :in $ ?run [?attribute ...]
                     :where [?run ?attribute ?member]]
                   value (:db/id run)
                   [:seon.test.run/members :seon.test.run/covered-by])))
          current (members-at database)
          selected-at (get-in run [:seon.test.run/selection-tx :db/id])
          selected (members-at (db/as-of database selected-at))]
      (when (not= (set selected) (set current))
        (execution-refusal! 'seon.test.runner/claim-member run-id
                            :seon.test/population-unknown selected current))
    (mapv
     (fn [member-id]
       (let [member (execution-read
       (db/pull database
                [:db/id :seon.test.member/symbol :seon.test.member/reasons
                 :seon.test.member/worker :seon.test.member/claimed-at
                 :seon.test.member/claim-tx :seon.test.member/host
                 :seon.test.member/completed-tx :seon.test.member/terminated-tx
                 :seon.test.member/pass-count :seon.test.member/fail-count
                 :seon.test.member/error-count :seon.test.member/began?
                 :seon.test.member/ended? :seon.test.member/error] member-id))
             counts (select-keys member [:seon.test.member/pass-count :seon.test.member/fail-count
                                         :seon.test.member/error-count])]
         (when (or (and (:seon.test.member/completed-tx member)
                        (or (not= 3 (count counts))
                            (not (boolean? (:seon.test.member/began? member)))
                            (not (boolean? (:seon.test.member/ended? member)))))
                   (and (seq counts) (not (:seon.test.member/completed-tx member))))
           (execution-refusal! 'seon.test.runner/claim-member run-id
                               :seon.test/population-unknown :complete-outcome member))
         member))
     current))))

(defn- worker-identity [database worker]
  (execution-read
   (db/pull database [:db/id :seon.db.process/id
                     :seon.db.process/pid :seon.db.process/start-instant] worker)))

(defn claim-member
  "Claim one remaining namespace group at the mid-transaction database.

  The supplied run's admitted and covered memberships are the only work list.
  Confirmed dead generations may be reclaimed; elapsed time is not death.
  The same process cannot hold two unterminated groups in this authority.
  A primary JVM spanning independent branches requires one routed claim
  authority; this pure function cannot serialize independent branch writers.
  An empty transaction means no remaining obligation (or a platform red).
  Read accepted claims from the transaction report, never a pre-read."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/claim-request]
                  :seon.store/transaction-data]}
  [database {run-id :seon.test.run/id worker :seon.test.member/worker
             instant :seon.test.member/claimed-at host :seon.test.member/host
             deadline :seon.test.run/deadline dead :seon.test.run/dead-workers
             :as request}]
  (let [operation 'seon.test.runner/claim-member
        identity-keys [:seon.db.process/pid :seon.db.process/start-instant]
        process (worker-identity database worker)
        process-identity (select-keys process identity-keys)
        declared-deadline (:seon.test.run/deadline
                           (execution-read
                            (db/pull database [:seon.test.run/deadline]
                                     [:seon.test.run/id run-id])))
        deaths (set dead)
        dead? (fn [member]
                (when-let [owner (get-in member [:seon.test.member/worker :db/id])]
                  (contains? deaths (select-keys (worker-identity database owner)
                                                identity-keys))))
        members (execution-members database run-id)
        unfinished (remove :seon.test.member/completed-tx members)
        platform? #(contains? (set (:seon.test.member/reasons %)) :platform)
        platform (filter platform? members)
        platform-red? (some #(and (:seon.test.member/completed-tx %)
                                 (pos? (+ (:seon.test.member/fail-count % 0)
                                          (:seon.test.member/error-count % 0)))) platform)]
    (when (or (not (:seon.db.process/id process))
              (not= process-identity (select-keys request identity-keys))
              (contains? deaths process-identity))
      (execution-refusal! operation run-id :seon.test/process-state-unknown
                          (select-keys request identity-keys) (or process :absent)))
    (when (or (not= declared-deadline deadline)
              (>= (inst-ms instant) (inst-ms deadline)))
      (execution-refusal! operation run-id ::worker-exchange-bound
                          {:seon.test.run/deadline deadline}
                          {:seon.test.member/claimed-at instant
                           :seon.test.member/worker worker}))
    (doseq [member unfinished]
      (when (and (:seon.test.member/claimed-at member)
                 (or (not (:seon.test.member/claim-tx member))
                     (not (:seon.test.member/worker member))))
        (execution-refusal! operation run-id :seon.test/process-state-unknown
                            :complete-claim member)))
    (if (or (empty? unfinished) platform-red?)
      []
      (let [held (execution-read
                  (db/q '[:find [?member ...] :in $ ?pid ?start
                          :where [?worker :seon.db.process/pid ?pid]
                                 [?worker :seon.db.process/start-instant ?start]
                                 [?member :seon.test.member/worker ?worker]
                                 [?member :seon.test.member/claim-tx]
                                 (not [?member :seon.test.member/terminated-tx])]
                        database (:seon.db.process/pid process)
                        (:seon.db.process/start-instant process)))
            platform-namespaces (set (map #(namespace (:seon.test.member/symbol %)) platform))
            eligible (if (some #(not (:seon.test.member/completed-tx %)) platform)
                       (filter #(contains? platform-namespaces
                                           (namespace (:seon.test.member/symbol %))) unfinished)
                       unfinished)
            groups (sort-by first (group-by #(namespace (:seon.test.member/symbol %)) eligible))
            group (some (fn [[_ candidates]]
                          (when (every? #(or (not (:seon.test.member/claimed-at %))
                                            (dead? %)) candidates)
                            candidates)) groups)]
        (when (or (seq held) (not group))
          (execution-refusal! operation run-id :seon.test/claim-conflict
                              :unclaimed-namespace-group
                              {:seon.test.member/worker worker
                               :seon.test.run/members (if (seq held) held (vec eligible))}))
        (mapv #(hash-map :db/id (:db/id %)
                         :seon.test.member/worker (:db/id process)
                         :seon.test.member/claimed-at instant
                         :seon.test.member/claim-tx "datomic.tx"
                         :seon.test.member/host host)
              (sort-by :seon.test.member/symbol group))))))

(defn- prepare-failures!
  [connection database completion]
  (let [run (:seon.test.run/provenance completion)
        tested-branch (or (:seon.test.run/tested-branch run) (:seon.test.run/branch run))
        tested (or (:seon.db/db completion)
                   (when (= tested-branch (get-in database [:config :branch]))
                     (db/as-of database (:seon.test.run/basis-t run)))
                   database)
        threshold (db/q '[:find ?n . :where [_ :seon.config.eval.result/blob-threshold ?n]] database)
        _ (when (:seon.error/kind threshold)
            (throw (ex-info "Cannot read the assertion blob threshold." threshold)))
        staged (volatile! [])
        stage-field
        (fn [failure field size-key blob-key]
          (if-let [text (get failure field)]
            (let [size (alength (.getBytes ^String text StandardCharsets/UTF_8))]
              (if (and threshold (> size threshold))
                (let [write (blob/stage! connection text)]
                  (vswap! staged conj write)
                  (-> failure (dissoc field)
                      (assoc size-key size blob-key (:seon.blob/digest write))))
                (assoc failure size-key size)))
            failure))
        results
        (mapv
          (fn [{test-symbol :seon.test/sym :as result}]
            (let [test-row (db/pull tested
                                   '[{:seon.fn/file [:seon.fn.file/relative-path]}]
                                   [:seon.test/sym test-symbol])
                  path (get-in test-row [:seon.fn/file :seon.fn.file/relative-path])
                  reports (or (seq (:seon.test.failure/reports result))
                              (when (pos? (+ (:seon.test/fail-count result 0)
                                             (:seon.test/error-count result 0)))
                                (let [event {:type (if (pos? (:seon.test/error-count result 0)) :error :fail)
                                             :message (or (:seon.test/failure-message result)
                                                          "The runner reported a failure without an assertion event.")}]
                                  [{:seon.test.failure/type (:type event)
                                    :seon.test.failure/message (:message event)
                                    :seon.test/failure-identity
                                    (failure-identity {} (symbol test-symbol) event)}])))
                  [_ failures]
                  (reduce
                    (fn [[ordinals failures] report]
                      (let [reported (:seon.test.failure/reported-file report)
                            line (:seon.test.failure/line report)
                            known-site? (and path reported line
                                             (= (.getName (io/file path)) (.getName (io/file reported))))
                            site (if known-site? [path line]
                                     (mapv report [:seon.test.failure/type :seon.test.failure/message
                                                   :seon.test.failure/expected :seon.test.failure/actual
                                                   :seon.test.failure/signature]))
                            ordinal (get ordinals site 0)
                            failure (cond-> (-> report
                                                (dissoc :seon.test.failure/reported-file :seon.test.failure/line)
                                                (assoc :seon.test.failure/id (id/id [test-symbol site ordinal])
                                                       :seon.test.failure/ordinal ordinal))
                                      known-site? (assoc :seon.test.failure/file [:seon.fn.file/relative-path path]
                                                         :seon.test.failure/line line))
                            failure (-> failure
                                        (stage-field :seon.test.failure/expected :seon.test.failure/expected-size :seon.test.failure/expected-blob)
                                        (stage-field :seon.test.failure/actual :seon.test.failure/actual-size :seon.test.failure/actual-blob))]
                        [(assoc ordinals site (inc ordinal)) (conj failures failure)]))
                    [{} []] reports)]
              (-> result (dissoc :seon.test.failure/reports)
                  (assoc :seon.test/failures failures))))
          (:seon.test.runner/results completion))]
    (assoc completion :seon.test.runner/results results :seon.blob/staged-writes @staged)))

(defn- failure-replacement-tx [database test-row-id failures run-ref at]
  (let [previous (when-not (string? test-row-id)
                   (db/pull database '[{:seon.test/failures [*]}] test-row-id))
        previous-by-id (into {} (map (juxt :seon.test.failure/id identity)) (:seon.test/failures previous))
        retained (set (map :seon.test.failure/id failures))]
    (into
      (mapv (fn [old] [:db.fn/retractEntity (:db/id old)])
            (remove #(retained (:seon.test.failure/id %)) (:seon.test/failures previous)))
      (mapcat
        (fn [{failure-id :seon.test.failure/id :as failure}]
          (let [old (or (get previous-by-id failure-id)
                        (db/pull database '[*] [:seon.test.failure/id failure-id]))
                old-run (get-in old [:seon.test.failure/last-run :db/id])
                current-run (:db/id (db/pull database [:db/id] run-ref))
                row (assoc failure
                           :db/id (or (:db/id old) (str "test-failure:" failure-id))
                           :seon.test.failure/test test-row-id
                           :seon.test.failure/first-run (or (get-in old [:seon.test.failure/first-run :db/id]) run-ref)
                           :seon.test.failure/last-run run-ref
                           :seon.test.failure/seen-count (+ (get old :seon.test.failure/seen-count 0)
                                                          (if (and current-run (= old-run current-run)) 0 1))
                           :seon.test.failure/last-seen-at at)]
            (concat
              ;; Retract ONLY what the new row does not assert. A
              ;; cardinality-one add replaces its own value and an identical
              ;; add emits no datom at all, so retracting every attribute
              ;; first turned an unchanged re-record into pure churn.
              (for [attribute (keys (dissoc old :db/id :seon.test.failure/id))
                    :when (not (contains? row attribute))]
                [:db.fn/retractAttribute (:db/id old) attribute])
              ;; The one cardinality-many failure attribute changes member by
              ;; member; only the members the new row drops are retracted.
              (let [wanted (set (:seon.test.failure/contexts row))]
                (for [context (:seon.test.failure/contexts old)
                      :when (not (contains? wanted context))]
                  [:db/retract (:db/id old) :seon.test.failure/contexts context]))
              [row]))) failures))))

(defn complete-members
  "Accept immutable member outcomes for an exact writer claim.

  Called by record-tx after the existing blob preparation. Only failing
  claims produce reports, keyed by the captured content signature. Program
  rows are never created. A later observation of termination may add its
  transaction without changing the recorded outcome."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/claim-completion]
                  :seon.store/transaction-data]}
  [database {run :seon.test.run/provenance results :seon.test.runner/results
             worker :seon.test.member/worker claim :seon.test.member/claim-tx
             terminated? :seon.test.run/terminated?}]
  (let [run-id (:seon.test.run/id run)
        operation 'seon.test.runner/record-tx
        members (into {} (map (juxt :seon.test.member/symbol identity))
                      (execution-members database run-id))
        worker-id (:db/id (worker-identity database worker))
        claim-id (:db/id (execution-read (db/pull database [:db/id] claim)))
        forms (:seon.schema.projection/forms (db/carried-projection database))
        provenance-attributes (mapv first (filter vector? (rest (get forms :seon.test.run/provenance))))
        recorded-run (execution-read
                      (db/pull database provenance-attributes [:seon.test.run/id run-id]))
        _ (when (not= (select-keys run provenance-attributes)
                      (dissoc recorded-run :db/id))
            (execution-refusal! operation run-id :seon.test.run/immutable
                                recorded-run run))
        report-attributes (mapv first (drop 2 (get forms :seon.test.report/report)))
        _ (when (empty? report-attributes)
            (execution-refusal! operation run-id :seon.test/population-unknown
                                :seon.test.report/report :absent))
        outcome-keys [:seon.test.member/pass-count :seon.test.member/fail-count
                      :seon.test.member/error-count :seon.test.member/began?
                      :seon.test.member/ended? :seon.test.member/error]
        prepared
        (mapv
         (fn [result]
           (let [test-symbol (symbol (:seon.test/sym result))
                 member (get members test-symbol)
                 reports
                 (mapv
                  (fn [failure]
                    (let [signature (:seon.test/failure-identity failure)
                          _ (when-not signature
                              (execution-refusal! operation run-id :seon.test/population-unknown
                                                  :captured-claim-signature failure))
                          path (when-let [file (:seon.test.failure/file failure)]
                                 (:seon.fn.file/relative-path
                                  (execution-read
                                   (db/pull database [:seon.fn.file/relative-path] file))))]
                      (cond-> (assoc (select-keys failure report-attributes)
                                     :seon.test.report/id (id/id [test-symbol signature])
                                     :seon.test.report/symbol test-symbol)
                        path (assoc :seon.test.failure/reported-file path))))
                  (:seon.test/failures result))
                 outcome (cond-> {:seon.test.member/pass-count (:seon.test/pass-count result)
                          :seon.test.member/fail-count (:seon.test/fail-count result)
                          :seon.test.member/error-count (:seon.test/error-count result)
                          :seon.test.member/began? (:seon.test.member/began? result)
                          :seon.test.member/ended? (:seon.test.member/ended? result)}
                           (:seon.test.member/error result)
                           (assoc :seon.test.member/error
                                  (execution-read
                                   (db/pull database [:db/id] (:seon.test.member/error result)))))]
             (when (or (not worker-id) (not claim-id) (not member)
                       (not= worker-id (get-in member [:seon.test.member/worker :db/id]))
                       (not= claim-id (get-in member [:seon.test.member/claim-tx :db/id])))
               (execution-refusal! operation run-id :seon.test/claim-replaced
                                   {:seon.test.member/worker worker
                                    :seon.test.member/claim-tx claim}
                                   (or member :absent)))
             (when (or (not (boolean? (:seon.test.member/began? result)))
                       (not (boolean? (:seon.test.member/ended? result)))
                       (and (pos? (+ (:seon.test/fail-count result)
                                     (:seon.test/error-count result)))
                            (empty? reports)))
               (execution-refusal! operation run-id :seon.test/population-unknown
                                   :complete-outcome-events result))
             {:seon.test.member/value member :seon.test.member/outcome outcome
              :seon.test.report/values reports})) results)
        _ (when (not= (count results) (count (set (map :seon.test/sym results))))
            (execution-refusal! operation run-id :seon.test.run/immutable
                                :one-outcome-per-member results))
        reports (group-by :seon.test.report/id (mapcat :seon.test.report/values prepared))
        report-tx
        (into []
              (keep (fn [[report-id variants]]
                      (let [wanted (first variants)
                            previous (execution-read
                                      (db/pull database (into [:db/id] report-attributes)
                                               [:seon.test.report/id report-id]))]
                        (when (or (not (apply = variants))
                                  (and (:db/id previous)
                                       (not= wanted (dissoc previous :db/id))))
                          (execution-refusal! operation run-id :seon.test/report-conflict
                                              wanted (or previous variants)))
                        (when-not (:db/id previous) wanted)))) reports)]
    (into report-tx
          (mapcat
           (fn [{member :seon.test.member/value outcome :seon.test.member/outcome
                 reports :seon.test.report/values}]
             (let [eid (:db/id member)
                   report-ids (set (map :seon.test.report/id reports))
                   old-report-ids (set (execution-read
                                       (db/q '[:find [?id ...] :in $ ?member
                                               :where [?member :seon.test.member/failures ?report]
                                                      [?report :seon.test.report/id ?id]]
                                             database eid)))
                   completed? (:seon.test.member/completed-tx member)]
               (when (and completed?
                          (or (not= outcome (select-keys member outcome-keys))
                              (not= report-ids old-report-ids)))
                 (execution-refusal! operation run-id :seon.test.run/immutable
                                     (select-keys member outcome-keys) outcome))
               (cond-> []
                 (not completed?)
                 (conj (cond-> (assoc outcome :db/id eid
                                      :seon.test.member/completed-tx "datomic.tx")
                         (seq report-ids)
                         (assoc :seon.test.member/failures
                                (set (map #(vector :seon.test.report/id %) report-ids)))))
                 (and terminated? (not (:seon.test.member/terminated-tx member)))
                 (conj [:db/add eid :seon.test.member/terminated-tx "datomic.tx"]))))
           prepared))))

(defn- record-latest-tx
  "Transaction data replacing each test row's complete latest result.

  The replacement is a DELTA and still total: every attribute, reach member
  and failure identity this result does not assert is retracted, and every
  one it asserts unchanged writes nothing. Retracting first and re-asserting
  identically cost 157,981 datoms for one unchanged 93-result completion
  (measured 2026-09-17 on `default`), which is what filled the store. This
  runs as a `:db.fn/call` transaction function, so the presence decision
  reads the WRITER's own database value — a caller pre-read could strand
  a retract's lookup ref against a concurrently retracted row and reject
  the whole result transaction."
  [database
   {results :seon.test.runner/results
    run :seon.test.run/provenance
    tested-database :seon.db/db
    destination :seon.test.run/branch
    reach-unknown :seon.test/reach-unknown
    carried-digests :seon.test/reach-digests
    carried-reaches :seon.test/reaches}]
  (let [tested-branch (or (:seon.test.run/tested-branch run) (:seon.test.run/branch run))
        tested (or tested-database
                   (when (= tested-branch (get-in database [:config :branch]))
                     (db/as-of database (:seon.test.run/basis-t run))))
        destination (or destination (get-in database [:config :branch]))
        run (cond-> (assoc run :seon.test.run/branch destination)
              (not= tested-branch destination) (assoc :seon.test.run/tested-branch tested-branch))
        digests (or carried-digests
                    (when tested (reach-digests tested (mapv :seon.test/sym results))))
        derived-reaches (when tested (reach-memberships tested (mapv :seon.test/sym results)))
        reaches (if (:seon.error/kind derived-reaches)
                  (or carried-reaches derived-reaches)
                  (merge derived-reaches carried-reaches))
        run-id (:seon.test.run/id run)
        basis-t (:seon.test.run/basis-t run)
        at (:seon.test.run/at run)
        run-ref [:seon.test.run/id run-id]
        current-by-symbol
        (into {}
              (keep (fn [{test-symbol :seon.test/sym}]
                      (when-let [row (db/pull database
                                              [:db/id
                                               :seon.test/reach-digest
                                               :seon.test/reach-unknown
                                               :seon.test/failure-message
                                               :seon.test/failing-assertions
                                               '(limit :seon.test/reach nil)]
                                              [:seon.test/sym test-symbol])]
                        [test-symbol row])))
              results)
        file-present?
        (memoize #(some? (db/pull database [:db/id] [:seon.fn.file/relative-path %])))
        ;; A file identity cannot be minted honestly: `:seon.fn.file/file`
        ;; requires the digest of the file the indexer walked. An absent site
        ;; keeps its line and reports its path as the typed unknown, when the
        ;; database being written into declares that attribute.
        reported-path? (some? (get (:schema database) :seon.test.failure/reported-file))
        portable-failure
        (fn [failure]
          (let [path (second (:seon.test.failure/file failure))]
            (if (or (nil? path) (file-present? path))
              failure
              (cond-> (dissoc failure :seon.test.failure/file)
                reported-path? (assoc :seon.test.failure/reported-file path)))))
        previous (db/pull database (vec (keys run)) run-ref)]
    (when (:seon.error/kind previous)
      (throw (ex-info (:seon.error/message previous) previous)))
    (when (and previous (not= run (dissoc previous :db/id)))
      (let [failure (error/diagnostic
                     {:seon.error/kind :seon.test.run/immutable
                      :seon.error/message "A test run's provenance is immutable."
                      :seon.error/diagnostic-layer :test
                      :seon.error/diagnostic-operation 'seon.test.runner/record-tx
                      :seon.error/diagnostic-member run-id
                      :seon.error/diagnostic-expected (dissoc previous :db/id)
                      :seon.error/diagnostic-offending run
                      :seon.error/diagnostic-cause :seon.test.run/immutable
                      :seon.error/diagnostic-evidence
                      {:seon.test.run/id run-id
                       :seon.db/basis-t (db/basis-t database)}
                      :seon.test.run/id run-id
                      :seon.test.run/immutable run-id})]
        (throw (ex-info (:seon.error/message failure) failure))))
    (let [missing (into [] (comp (map :seon.test/sym) (remove current-by-symbol)) results)]
      (when (seq missing)
        (throw (ex-info "Test completion has no surviving test definition."
                        {:seon.error/kind ::test-definition-absent
                         :seon.test/symbols missing}))))
    (into [(assoc run :db/id "test-run")]
     (mapcat
      (fn [{test-symbol :seon.test/sym :as result}]
        (let [test-ref [:seon.test/sym test-symbol]
              current (get current-by-symbol test-symbol)
              exists? (some? current)
              test-row-id test-ref
              failures (mapv portable-failure (:seon.test/failures result))
              wanted-reach (when (set? (get reaches test-symbol))
                             (get reaches test-symbol))
              wanted-members (set wanted-reach)
              ;; The reach is replaced member by member: an unchanged
              ;; membership emits nothing, a dropped member emits its own
              ;; retraction, a new member its own assertion. Retracting the
              ;; whole attribute first cost 149,436 datoms for 93 identical
              ;; results (measured 2026-09-17 on `default`).
              held-members (set (:seon.test/reach current))
              retracted-members (mapv #(vector :db/retract test-ref :seon.test/reach %)
                                      (remove wanted-members held-members))
              added-members (into #{} (remove held-members) wanted-reach)
              wanted-digest (get digests test-symbol)
              wanted-assertions (set (:seon.test/failing-assertions result))
              result-row
              (cond-> (assoc (dissoc result :seon.test/failures)
                             :db/id test-row-id
                             :seon.test/run "test-run"
                             :seon.test/run-basis-t basis-t
                             :seon.test/run-at at)
                (string? wanted-digest)
                (assoc :seon.test/reach-digest wanted-digest)
                (seq added-members)
                (assoc :seon.test/reach added-members)
                (nil? wanted-reach)
                (assoc :seon.test/reach-unknown
                       (or (:seon.error/message reaches)
                           reach-unknown
                           "The completion did not retain its tested database closure membership."))
                (seq failures) (assoc :seon.test/failures
                                      (mapv (fn [failure]
                                              (let [failure-id (:seon.test.failure/id failure)]
                                                (or (:db/id (db/pull database [:db/id]
                                                              [:seon.test.failure/id failure-id]))
                                                    (str "test-failure:" failure-id))))
                                            failures))
)]
          (into (cond-> []
            ;; An attribute is retracted ONLY when this result does not
            ;; assert it: a cardinality-one add replaces its own value, and
            ;; Datahike emits nothing for an identical add. The update stays
            ;; total — a green run still clears the stale failure evidence —
            ;; while an unchanged re-record writes no datom.
            (and exists? (not (contains? result :seon.test/failure-message))
                 (contains? current :seon.test/failure-message))
            (conj [:db.fn/retractAttribute test-ref :seon.test/failure-message])

            (and exists? (not (string? wanted-digest))
                 (contains? current :seon.test/reach-digest))
            (conj [:db.fn/retractAttribute test-ref :seon.test/reach-digest])

            (and exists? (some? wanted-reach)
                 (contains? current :seon.test/reach-unknown))
            (conj [:db.fn/retractAttribute test-ref :seon.test/reach-unknown])

            exists?
            (into (comp (remove wanted-assertions)
                        (map (fn [assertion]
                               [:db/retract test-ref :seon.test/failing-assertions
                                assertion])))
                  (:seon.test/failing-assertions current))

            exists? (into retracted-members)
            true (conj result-row))
            (failure-replacement-tx database test-row-id failures run-ref at))))
      results))))

(defn record-tx
  "Record one completion at the writer, using its admitted claim when present.

  Admitted runs complete members without recreating program rows. Legacy
  unadmitted runs retain the existing latest-result replacement until their
  callers migrate to pre-execution admission."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/completion]
                  :seon.test.runner/record-tx]}
  [database completion]
  (let [run-id (get-in completion [:seon.test.run/provenance :seon.test.run/id])
        row (execution-read
             (db/pull database [:seon.test.run/selection-tx] [:seon.test.run/id run-id]))]
    (if (or (:seon.test.run/selection-tx row)
            (:seon.test.member/claim-tx completion)
            (:seon.test.member/worker completion))
      (execution-read (complete-members database completion))
      (record-latest-tx database completion))))

(def ^:private result-selector
  [:seon.test/reach-digest
   :seon.test/reach-unknown
   :seon.test/sym
   :seon.test/pass-count
   :seon.test/fail-count
   :seon.test/error-count
   :seon.test/run-basis-t
   :seon.test/run-at
   :seon.test/run
   {:seon.test/failures ['* {:seon.test.failure/file [:db/id :seon.fn.file/relative-path]}]}
   :seon.test/failing-assertions
   :seon.test/failure-message])

(defn reusable-result
  "Return the recorded green result for this identical single-test request.

  A bare request uses its last green program basis. An explicit basis must
  match that evidence, or be the current basis for fresh execution. Selection
  is read from recorded history, so a batch does not become a single-test
  request merely because other tests acquired newer results. No body runs
  and no transaction is written on a hit."
  {:malli/schema [:=> [:cat :seon.test/reuse-request] :seon.test/reuse-result]}
  [{database :seon.db/db test-symbol :seon.test/identity requested :seon.test/run-basis-t}]
  (try
    (let [current-t (db/basis-t database)
          _ (when (and requested (> requested current-t))
              (execution-refusal! 'seon.test.runner/reusable-result (str test-symbol)
                                  :seon.test/invalid-basis current-t requested))
          row (execution-read (db/pull database result-selector [:seon.test/sym test-symbol]))
          run-id (get-in row [:seon.test/run :db/id])
          run (when run-id
                (execution-read
                 (db/pull database [:seon.test.run/program-digest :seon.test.run/basis-t
                                    :seon.test.run/branch :seon.test.run/tested-branch
                                    :seon.test.run/selection-tx] run-id)))
          digest (execution-read (program-digest database))
          recorded-t (when run-id
                       (execution-read
                        (db/q '[:find (max ?t) . :in $ ?test [?attribute ...]
                                :where [?test ?attribute _ ?t]]
                              database (:db/id row) (vec (filter keyword? result-selector)))))
          selected (when (and run-id recorded-t)
                     (execution-read
                      (db/q '[:find [?symbol ...] :in $ ?run
                              :where [?test :seon.test/run ?run]
                                     [?test :seon.test/sym ?symbol]]
                            (db/history (db/as-of database recorded-t)) run-id)))
          counts (mapv #(get row %) [:seon.test/pass-count :seon.test/fail-count :seon.test/error-count])
          reusable? (and recorded-t
                         (every? #(and (integer? %) (not (neg? %))) counts)
                         (zero? (:seon.test/fail-count row))
                         (zero? (:seon.test/error-count row))
                         (= digest (:seon.test.run/program-digest run))
                         (= (get-in database [:config :branch]) (:seon.test.run/branch run))
                         (not (:seon.test.run/tested-branch run))
                         (= (:seon.test/run-basis-t row) (:seon.test.run/basis-t run))
                         (= #{test-symbol} (set selected))
                         (or (nil? requested) (= requested (:seon.test.run/basis-t run))))]
      (cond
        reusable? (assoc (dissoc row :db/id)
                         :seon.test/unchanged true :seon.test/recorded-basis-t recorded-t)
        (and requested (not= requested current-t))
        (execution-refusal! 'seon.test.runner/reusable-result (str test-symbol)
                            :seon.test/invalid-basis
                            {:seon.test/run-basis-t current-t
                             :seon.test/unchanged :matching-recorded-green-basis}
                            requested)
        :else :seon.test/execution-required))
    (catch Exception failure
      (if (:seon.error/kind (ex-data failure))
        (ex-data failure)
        (error/diagnostic
         {:seon.error/kind :seon.test/population-unknown
          :seon.error/message "Recorded test evidence could not be read."
          :seon.error/diagnostic-layer :test-reuse
          :seon.error/diagnostic-operation 'seon.test.runner/reusable-result
          :seon.error/diagnostic-member test-symbol
          :seon.error/diagnostic-expected :recorded-program-selection-and-basis
          :seon.error/diagnostic-offending (or (ex-message failure) (.getName (class failure)))
          :seon.error/diagnostic-cause :unavailable-recorded-evidence
          :seon.error/diagnostic-evidence {:seon.test/run-basis-t (db/basis-t database)}})))))

(defn- recorded-member-result [database run test-symbol]
  (let [member (first (filter #(= (symbol test-symbol) (:seon.test.member/symbol %))
                             (execution-members database (:seon.test.run/id run))))
        report-ids (execution-read
                    (db/q '[:find [?report ...] :in $ ?member
                            :where [?member :seon.test.member/failures ?report]]
                          database (:db/id member)))
        reports (execution-read (db/pull-many database '[*] report-ids))]
    (cond-> {:seon.test/sym test-symbol
             :seon.test/pass-count (:seon.test.member/pass-count member)
             :seon.test/fail-count (:seon.test.member/fail-count member)
             :seon.test/error-count (:seon.test.member/error-count member)
             :seon.test/run-basis-t (:seon.test.run/basis-t run)
             :seon.test/run-at (:seon.test.run/at run)
             :seon.test/run [:seon.test.run/id (:seon.test.run/id run)]
             :seon.test.member/completed-tx (:seon.test.member/completed-tx member)}
      (seq reports) (assoc :seon.test.failure/reports (mapv #(dissoc % :db/id) reports)
                          :seon.test/failure-message
                          (str/join "\n\n" (map (requiring-resolve 'seon.test/failure-text) reports))))))

(defn commit-results!
  "Commit captured test results and return those exact committed facts."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.test.run/completion]
    [:or :seon.test/results :seon.error/value]]}
  [connection {results :seon.test.runner/results :as completion}]
  (let [database (db/db connection)
        completion (prepare-failures! connection database completion)
        tested (:seon.db/db completion)
        completion (if tested
                     (assoc (dissoc completion :seon.db/db)
                            :seon.test/reach-digests
                            (reach-digests tested (mapv :seon.test/sym results))
                            :seon.test/reaches
                            (reach-memberships tested (mapv :seon.test/sym results)))
                     completion)
        transaction-report
        (if (:seon.error/kind database)
          database
          (blob/with-publication! connection (:seon.blob/staged-writes completion)
            #(db/transact! connection
                           [[:db.fn/call #'record-tx (dissoc completion :seon.blob/staged-writes)]]))) ]
    (if (:seon.error/kind transaction-report)
      transaction-report
      (let [recorded (mapv (fn [{test-symbol :seon.test/sym}]
              (if (:seon.test.member/claim-tx completion)
                (recorded-member-result (:db-after transaction-report)
                                        (:seon.test.run/provenance completion) test-symbol)
                (dissoc
                 (db/pull (:db-after transaction-report)
                          result-selector
                          [:seon.test/sym test-symbol])
                 :db/id)))
            results)]
        (or (first (filter :seon.error/kind recorded)) recorded)))))

(defn- start-cluster!
  [cluster-name root]
  (let [start! @cluster-start!
        stop! @cluster-stop!]
    (try
      (start! {:seon.boot/cluster-name cluster-name
               :seon.boot/root root})
      (catch Throwable failure
        (when-let [instance (:seon.boot/instance (ex-data failure))]
          (stop! instance))
        (throw failure)))))

(defn- completion-reach-digests
  "Carry reach evidence from the canonical fixture's tested program across a JVM boundary."
  [run-result]
  (if (and (:seon.test/reach-digests run-result) (:seon.test/reaches run-result))
    run-result
    (try
     ;; `seon.test-support` lives under `test/`: a deliberate late dependency.
     ((requiring-resolve 'seon.test-support/with-database)
     (fn [connection]
       (let [database (db/db connection)
             digest (program-digest database)
             symbols (mapv :seon.test/sym (:seon.test.runner/results run-result))]
         (if (= digest (:seon.test.run/program-digest run-result))
           (assoc run-result :seon.test/reach-digests (reach-digests database symbols)
                  :seon.test/reaches (reach-memberships database symbols))
           (assoc run-result :seon.test/reach-unknown
                  "The canonical fixture no longer identifies the tested program.")))))
     (catch Exception failure
       (assoc run-result :seon.test/reach-unknown
              (str "Tested closure unavailable: " (ex-message failure)))))))

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
  (let [run-result (completion-reach-digests run-result)
        instance (start-cluster! cluster-name root)]
    (try
      (let [connection (:seon.boot/cluster-connection instance)
            completion
            {:seon.test/reach-digests (:seon.test/reach-digests run-result)
             :seon.test/reaches (:seon.test/reaches run-result)
             :seon.test/reach-unknown (:seon.test/reach-unknown run-result)
             :seon.test.runner/results
             (:seon.test.runner/results run-result)
             :seon.test/run-basis-t (:seon.test.run/basis-t run-result)
             :seon.test/run-at (:seon.test.run/at run-result)
             :seon.test.run/provenance
             (select-keys run-result
                          [:seon.test.run/id :seon.test.run/at
                           :seon.test.run/git-sha :seon.test.run/program-digest
                           :seon.test.run/basis-t :seon.test.run/branch])}]
        (commit-results! connection completion))
      (finally
        (@cluster-stop! instance)))))

(defn- commit-persistent-results!
  "Commit one completion through the source publication owner."
  [held-store run-result]
  (source/record-results!
   held-store
   {:seon.test/reach-digests (:seon.test/reach-digests run-result)
    :seon.test/reaches (:seon.test/reaches run-result)
    :seon.test/reach-unknown (:seon.test/reach-unknown run-result)
    :seon.test.runner/results (:seon.test.runner/results run-result)
    :seon.test/run-basis-t (:seon.test.run/basis-t run-result)
    :seon.test/run-at (:seon.test.run/at run-result)
    :seon.test.run/provenance
    (select-keys run-result [:seon.test.run/id :seon.test.run/at
                            :seon.test.run/git-sha :seon.test.run/program-digest
                            :seon.test.run/basis-t :seon.test.run/branch])}))

(defn- staged-completion
  "The gate completion staged at `path`, or a typed refusal naming it.

  A missing or unreadable file is named — never read as an empty completion."
  [path]
  (let [file (io/file (str path))]
    (if-not (.isFile file)
      {:seon.error/kind ::staged-completion-unreadable
       :seon.error/message (str "No staged gate completion file at " path ".")
       ::completion-path (str path)}
      (try
        (let [value (edn/read-string (slurp file))]
          (if (map? value)
            value
            {:seon.error/kind ::staged-completion-unreadable
             :seon.error/message
             (str "The staged gate completion at " path
                  " did not read as a completion map but as "
                  (.getName (class value)) ".")
             ::completion-path (str path)}))
        (catch Throwable failure
          {:seon.error/kind ::staged-completion-unreadable
           :seon.error/message
           (str "The staged gate completion at " path
                " did not read as EDN: " (ex-message failure))
           ::completion-path (str path)})))))

(defn- commit-staged-completion!
  "Read one staged completion off disk and commit it through the held store.

  The cluster end of the recording seam: the coordinator sends a path, never
  the completion itself, so the compiled form stays O(1) in the result count."
  [held-store path]
  (let [completion (staged-completion path)]
    (if (:seon.error/kind completion)
      completion
      (commit-persistent-results! held-store completion))))

(defn- stage-completion!
  "Write one completion as EDN under the run root; the sent form names its path.

  Inlining the completion as a literal compiled one method per gate whose
  bytecode exceeded the JVM's 64 KB limit once a run carried enough results."
  [run-result]
  (let [directory (io/file (or (System/getProperty "seon.test.root")
                               (System/getProperty "seon.test.source-root")
                               ".")
                           "tmp")]
    (.mkdirs directory)
    (let [file (io/file directory
                        (str "gate-completion-"
                             (or (:seon.test.run/id run-result) "run")
                             "-" (System/nanoTime) ".edn"))]
      (spit file (pr-str run-result))
      (.getCanonicalFile file))))

(defn- persistent-results-form
  "The prepl form recording one staged completion: O(1) in the result count."
  [completion-path]
  (pr-str
   `(try
      (require 'seon.test.runner)
      (let [store#
            (some :seon.store/store
                  (vals @(var-get
                          (ns-resolve 'seon.cluster
                                      (symbol "running-instances")))))]
        (if store#
          ((deref (ns-resolve 'seon.test.runner
                              (symbol "commit-staged-completion!")))
           store# ~(str completion-path))
          {:seon.error/kind
           :seon.test.runner/live-store-unavailable
           :seon.error/message
           "The live process has no held operator store."}))
      (catch Throwable failure#
        {:seon.error/kind
         (or (:seon.error/kind (ex-data failure#))
             :seon.test.runner/persistent-results-recording-failed)
         :seon.error/message (ex-message failure#)}))))

(defn- record-persistent-results!
  "Commit one bare-gate completion through the authoritative store holder."
  [operator-root run-result]
  (let [run-result (completion-reach-digests run-result)
        completion-file (stage-completion! run-result)]
    (try
      (let [{live? :seon.fresh-operator/live-process?
             value :seon.fresh-operator/value}
            ;; `seon.fresh-operator` lives under `script/`: a deliberate late
            ;; dependency of the operator drill, never a load-cycle dodge.
            ((requiring-resolve 'seon.fresh-operator/live-root-value!)
             operator-root (persistent-results-form (str completion-file)))]
        (if live?
          value
          (let [held-store
                (store/open-store!
                 {:seon.store/dir (str (io/file operator-root "data" "store"))})]
            (try
              (commit-persistent-results! held-store run-result)
              (finally
                (store/release-store! held-store))))))
      (finally
        (io/delete-file completion-file true)))))

(defn- configured-persistent-results-root
  [launcher-root explicit-root]
  (or (not-empty explicit-root) launcher-root))

(defn- recording-failure
  [record-fn]
  (try
    (let [result (record-fn)]
      (cond
        (:seon.error/kind result) result
        (and (vector? result) (every? :seon.test/run result)) nil
        :else {:seon.error/kind ::persistent-results-recording-failed
               :seon.error/message
               "The recorder returned no committed result references."}))
    (catch Throwable failure
      (let [data (ex-data failure)]
        (cond-> {:seon.error/kind
                 (or (:seon.error/kind data)
                     ::persistent-results-recording-failed)
                 :seon.error/message
                 (or (ex-message failure) (.getName (class failure)))}
          (seq data) (assoc :seon.error/data data))))))

(defn- recording-failure-notice
  "The gate line for a refused recording, carrying the cluster's own cause.

  The refusal names what was missing: its kind, its message, and the data the
  raiser attached — never a kind and a sentence with the evidence dropped."
  [recording-label failure]
  (let [cause
        (when-let [data (not-empty
                         (dissoc (:seon.error/data failure)
                                 :seon.fresh-operator/events))]
          (let [text (pr-str data)]
            (if (> (count text) 4000)
              (str (subs text 0 4000) "…")
              text)))]
    (str/join
     " "
     (remove nil?
             [(str "bin/test: " recording-label " NOT recorded:")
              (str (:seon.error/kind failure))
              (:seon.error/message failure)
              cause]))))

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

(defn- bare-selection-refusal
  "Refuse checkout selection until the launcher supplies named-cluster custody."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name] :seon.error/value]}
  [cluster-name]
  (error/diagnostic
   {:seon.error/kind (if (= "-" cluster-name)
                       :seon.test/cluster-required
                       :seon.test/selection-authority-unavailable)
    :seon.error/message
    "Bare bin/test requires an explicitly named cluster and its immutable published database at seon.test/select; the checkout coordinator has only publication provenance."
    :seon.error/diagnostic-layer :test
    :seon.error/diagnostic-operation 'seon.test/select
    :seon.error/diagnostic-member :seon.db/db
    :seon.error/diagnostic-expected
    {:seon.test.run/cluster :explicit-cluster-ref
     :seon.db/db :published-database-value}
    :seon.error/diagnostic-offending cluster-name
    :seon.error/diagnostic-cause :seon.test/selection-authority-unavailable
    :seon.error/diagnostic-evidence
    {:seon.boot/cluster-name cluster-name
     :seon.test.run/policy :incremental}}))

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
  ([]
   (worker-count (.availableProcessors (Runtime/getRuntime))
                 (System/getProperty "seon.test.worker-count")))
  ([processors prepared]
   (if prepared
     (let [n (Long/parseLong prepared)]
       (when-not (pos? n)
         (throw (ex-info "Prepared worker count must be positive."
                         {::worker-count n})))
       n)
     (max 1 (quot processors 2)))))

(defn- worker-parent
  []
  (let [parent (System/getProperty "seon.test.worker-parent")
        root (System/getProperty "seon.test.root")]
    (cond
      parent (io/file parent)
      root (io/file root "workers")
      ;; Neither property set: `(io/file nil "workers")` would silently
      ;; resolve to the JVM's working directory and write worker exhaust
      ;; into the source checkout, which is how a bare `workers/` tree
      ;; appeared at the repository root.
      :else (throw (ex-info "Worker parent needs seon.test.root or seon.test.worker-parent."
                            {::worker-parent nil})))))

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
  [worker expected exit-future phase]
  (loop []
    (if-let [line (.readLine ^BufferedReader (::worker-reader worker))]
      (if (str/starts-with? line protocol-prefix)
        (let [reply
              (try
                (edn/read-string (subs line (count protocol-prefix)))
                (catch Throwable _ ::unparseable-worker-reply))]
          (if (and (map? reply)
                   (= (::worker-id expected) (::worker-id reply))
                   (= (::exchange-id expected) (::exchange-id reply))
                   (#{:re-arming :armed} (::worker-event reply)))
            (do (reset! phase (::worker-event reply)) (recur))
            (if (and (map? reply) (matching-worker-reply? expected reply))
              {::exchange-terminal :reply ::exchange-reply reply}
              (do
                (append-worker-line! worker
                                     (str "UNMATCHED_WORKER_REPLY " line))
                (recur)))))
        (do
          (append-worker-line! worker line)
          (recur)))
      ;; Pipe EOF is not a fourth terminal event. Await the already-registered
      ;; exact process exit; if the process stays live, the declared bound wins.
      (.join ^CompletableFuture exit-future))))

(defn- event-backstop-seconds
  []
  ;; `seon.test-support` lives under `test/`: a deliberate late dependency.
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
            phase (atom :dispatched)
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
                 (read-exchange-reply! worker expected exit-future phase)))
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
                                  ;; Drain ordered protocol evidence before
                                  ;; reporting the process exit it precedes.
                                  [reply-future bound-future])))]
                (case terminal
                  :reply (::exchange-reply outcome)
                  :exit
                  (do
                    (retire-worker! worker false)
                    (assoc dispatch
                           ::dispatch-journal journal
                           ::worker-exchange-failure true
                           :seon.error/kind (if (= :re-arming @phase)
                                              ::re-arm-failed
                                              ::worker-exited)
                           ::worker-phase @phase
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
  (cache/worker-checkout! (System/getProperty "seon.test.root")
                          (.getPath checkout-root))
  (.mkdirs (io/file operator-root "logs"))
  (let [error-log (io/file operator-root "logs" "worker-stderr.log")
        published-base (System/getProperty "seon.test.published-base")
        basis-file (System/getProperty "seon.test.classpath-basis")
        basis (when basis-file (edn/read-string (slurp basis-file)))
        _ (when-not basis
            (throw (ex-info "A worker needs the resolved test classpath basis."
                            {:seon.error/kind :seon.test/classpath-unavailable})))
        command (cond-> [(or (System/getenv "SEON_TEST_CLOJURE") "clojure")
                         "-Scp" (cache/classpath basis (.getCanonicalPath checkout-root))
                         (str "-J-Dseon.operator.root="
                              (.getCanonicalPath operator-root))
                         (str "-J-Dseon.test.root="
                              (.getCanonicalPath operator-root))
                         (str "-J-Dseon.test.source-root=" (source-root))]
                  published-base
                  (conj (str "-J-Dseon.test.published-base=" published-base))
                  true
                  (into (map #(str "-J" %) (:seon.test/jvm-options basis)))
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
    (println "bin/test: WORKER READY" (pr-str ready))
    (flush)
    (cond-> worker
      (::fixture-preparation-ms ready)
      (assoc ::fixture-preparation-ms (::fixture-preparation-ms ready)))))

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

(defn- task-exchange-bound-seconds
  "The per-exchange bound for one task, widened by its declared allowance.

  The ordinary bound stands in for `a worker exchange that should have
  answered by now`. A `:seon.test/long` test is the declared exception, and
  `:seon.test/long-ms` states how long it legitimately takes — so the bound
  DERIVES from that declaration instead of expiring a test the program
  already said would run longer."
  ([task] (task-exchange-bound-seconds task bounds/fixture-priming-ms))
  ([task preparation-ms]
   (max (exchange-bound-seconds)
        (bounds/exchange-seconds (or (::task-long-ms task) 0)
                                 preparation-ms))))

(defn- execute-bounded-worker-task!
  [progress worker task bound-seconds]
  (let [result
        (worker-exchange!
         {::worker worker
          ::exchange-command {::worker-command :run ::worker-task task}
          ::exchange-id (::task-id task)
          ::expected-worker-event :task-complete
          ::task-symbols (::task-symbols task)
          ::completion-bound-seconds bound-seconds})
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

(defn- task-bound-notice
  "The coordinator's one line naming the bound it applied to a task and why."
  [worker-id task bound]
  (str "BEGIN worker=" worker-id
       " task=" (str/join "," (::task-symbols task))
       " bound=" bound "s "
       (if (::task-long-ms task)
         (str "(declared :seon.test/long-ms " (::task-long-ms task)
              " — " (::task-long-reason task) ")")
         "(default per-exchange bound)")
       "; includes measured fixture priming"))

(defn- execute-worker-task!
  [progress worker task]
  (let [default-bound (exchange-bound-seconds)
        bound (task-exchange-bound-seconds
               task (or (::fixture-preparation-ms worker) bounds/fixture-priming-ms))
        allowance (- bound default-bound)]
    (announce! progress (task-bound-notice (::worker-id worker) task bound))
    ;; The suite's silence horizon is widened for exactly as long as this
    ;; declared-long exchange is in flight, so the watchdog cannot win the
    ;; race the declaration already resolved.
    (when (pos? allowance)
      (swap! progress assoc-in [::silence-allowances (::task-id task)] allowance))
    (try
      (execute-bounded-worker-task! progress worker task bound)
      (finally
        (when (pos? allowance)
          (swap! progress update ::silence-allowances dissoc (::task-id task)))))))

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
                  (mapv #(execute-worker-task! progress (force serial-worker) %)
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
              (if (and (force serial-worker)
                       (not @(::worker-retired? (force serial-worker))))
                (mapv #(execute-worker-task! progress (force serial-worker) %)
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
    (cond-> (assoc task-result ::parallel-failure :unconfirmed
                   ::confirmation-failure failure-fact)
      (not (::task-summary task-result))
      (assoc ::task-summary {::test-count (count (::task-symbols task-result))
                             ::pass-count 0 ::fail-count 0
                             ::error-count (count (::task-symbols task-result))}
             ::task-results
             (mapv (fn [test-symbol]
                     {:seon.test/sym test-symbol
                      :seon.test/pass-count 0 :seon.test/fail-count 0
                      :seon.test/error-count 1
                      :seon.test/failing-assertions [(id/id [test-symbol failure-fact] 64)]
                      :seon.test/failure-message (pr-str failure-fact)})
                   (::task-symbols task-result))))))

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
            classification (if (::task-summary task-result)
                             (parallel-failure-classification task-result confirmation)
                             (if (task-red? confirmation) :failed :passed))
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
        (if-not (::task-summary task-result)
          confirmation
          (cond-> (assoc task-result
                       ::parallel-failure classification
                       ::confirmation-result confirmation)
          (and (= :parallel-only classification) (seq suspects))
          (assoc ::parallel-only-suspects
                 (mapv ::task-symbols suspects)))))
      (finally
        (stop-worker! worker)))))

(defn- confirm-task-results!
  "Confirm resolved pool failures concurrently while preserving result order."
  [parallelism progress resolved-task-ids task-results confirm!]
  (let [failures (filterv #(and (or (nil? (::task-summary %)) (task-red? %))
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
    (flush)
    (let [failure (when record-results! (recording-failure record-results!))]
      (when failure
        (println (recording-failure-notice recording-label failure)))
      (when (and green? (nil? failure) (::digests bulk))
        (record-green-basis! selection-mode git-sha (::digests bulk)))
      (flush)
      (if (and green? (nil? failure)) 0 1))))

(defn- confirmation-symbols
  []
  (into #{} (remove str/blank?)
        (str/split-lines (or (System/getProperty "seon.test.confirm") ""))))

(defn- confirmation-vars
  [all-vars symbols]
  (let [by-symbol (into {} (map (juxt (comp str var-symbol) identity)) all-vars)
        absent (set/difference symbols (set (keys by-symbol)))]
    (when (seq absent)
      (throw (ex-info "Named confirmation tests are unavailable."
                      {::missing-tests (vec (sort absent))})))
    (mapv by-symbol (sort symbols))))

(defn- run-parallel-stage!
  [namespaces progress manifest workers serial-worker tasks]
  (let [{::keys [resolved unresolved]} (split-resolved-tasks manifest tasks)]
    (when (seq unresolved)
      (println "bin/test:" (count unresolved)
               "task(s) lack complete :seon.test rows; running serially:")
      (doseq [task unresolved]
        (println " -" (str/join "," (::task-symbols task)))))
    (let [results (if (seq (confirmation-symbols))
                    (confirm-task-results!
                     (worker-count) progress (set (map ::task-id tasks)) tasks
                     (partial confirm-parallel-failure! namespaces))
                    (run-task-pool! progress workers serial-worker
                                    resolved unresolved))]
      (print-task-failures! results)
      {::task-results results
       ::task-summary (summarize-task-results results)})))

(defn- program-manifest
  "The program manifest this invocation selects and discovers namespaces over."
  []
  (if-let [base (System/getProperty "seon.test.published-base")]
    (cache/manifest base)
    (seon.fn/build-manifest
     {:seon.fn/roots selection/graph-roots})))

(defn- run-coordinator!
  "Run selected tests with progress and a liveness backstop.

  Explicit tiers run the declared `:seon.test/platform` regressions first
  and stop there when red. Bare `changed` requests return a typed refusal
  before worker startup until named-cluster selection custody is supplied.
  Record results in either the explicitly named non-default cluster
  or the persistent operator-owned branch selected by the launcher, then exit
  zero exactly when no test failed or errored and its evidence was recorded."
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
  (if (= "changed" selection-mode)
    (do (prn (bare-selection-refusal cluster-name)) 2)
  (let [manifest (program-manifest)
        ;; Named namespaces are the selection; with none named, the gate's
        ;; membership is a FACT read from the manifest the base already
        ;; wrote, so the shell never munges filenames into symbols. The read
        ;; precedes the worker launch because the workers are handed this
        ;; set: `manifest.edn` is written by both base-preparation paths
        ;; before the coordinator is launched at all, so nothing waits on it.
        namespaces (if (seq namespace-names)
                     (mapv symbol namespace-names)
                     (bare-namespaces manifest))
        progress (atom {::description "JVM runner initialized"
                        ::at-nanos (System/nanoTime)
                        ::at (Instant/now)})
        suite-start (Instant/now)
        configured-silence-seconds (silence-seconds)
        backstop (start-liveness-backstop!
                  progress configured-silence-seconds suite-start)
        pool-size (worker-count)
        confirming (confirmation-symbols)
        worker-ids (if (seq confirming) []
                      (mapv #(str "pool-" %) (range 1 (inc pool-size))))
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
        (announce! progress "SELECT partitioning tiers over the program graph")
        (let [tested-program (edn/read-string
                              (slurp (io/file
                                      (System/getProperty "seon.test.published-base")
                                      "provenance.edn")))
              run-provenance (assoc tested-program
                                    :seon.test.run/id (id/id)
                                    :seon.test.run/at (java.util.Date.)
                                    :seon.test.run/git-sha git-sha)
              workers (mapv #(.get %) worker-futures)
              pool-workers (filterv #(str/starts-with? (::worker-id %) "pool-")
                                    workers)
              serial-worker
              (delay
                (let [checkout (worker-checkout "serial")
                      worker (start-worker! "serial" checkout checkout)]
                  (swap! workers* conj worker)
                  (initialize-worker! worker namespaces)))
              explicit? (= "explicit" selection-mode)
              bulk (case selection-mode
                     ("all" "full")
                     {::symbols :all ::reason (str "the " selection-mode " tier")
                      ::digests (selection/input-digests ".")}
                     "platform" {::symbols #{} ::reason "platform tier only"}
                     nil)
              all-vars (test-vars-in namespaces)
              declarations (long-declarations manifest)
              _ (verify-long-declarations-indexed! declarations all-vars)
              platform-rows (platform-declarations manifest)
              _ (verify-platform-declarations-indexed! platform-rows all-vars)
            {::keys [platform selected skipped unreached]}
            (if explicit?
              {::platform [] ::selected (if (seq confirming)
                                         (confirmation-vars all-vars confirming)
                                         all-vars)
               ::skipped [] ::unreached []}
              (test-selection namespaces
                              {::include-long? (= "full" selection-mode)
                               ::long-declarations declarations
                               ::platform-declarations platform-rows
                               ::selected-symbols (::symbols bulk)}))
            _ (when bulk
                (announce! progress
                           (str "SELECTION " selection-mode " — "
                                (::reason bulk)
                                "; platform " (count platform)
                                ", bulk " (count selected)
                                ", not reached " (count unreached))))
              platform-tasks (test-tasks all-vars platform declarations)
              selected-tasks (test-tasks all-vars selected declarations)
              _ (verify-platform-tier-carries-no-destructive-drill!
                 manifest platform)
              _ (verify-fixture-observations! manifest (concat platform selected))
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
              (assoc run-provenance
               :seon.test.runner/summary summary
               :seon.test.runner/results
               (into [] (mapcat ::task-results) task-results)
               ::stopped-after (when platform-red? :platform))
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
          (catch IllegalStateException _)))))))

(defn- coordinator-main!
  [cluster-name root git-sha selection-mode namespace-names]
  (let [projection (packaged-test-projection "coordinator")]
    (schema/call-with-projection
     projection
     #(run-coordinator! cluster-name root git-sha selection-mode
                        namespace-names))))

(defn -main
  "Run the coordinator, publish its full or incremental base, or run a worker."
  {:malli/schema [:=> [:cat [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Clojure's command-line entry point receives any number of string arguments; the command parser owns option combinations and their diagnostics.", :gen/elements [[]]} :string]] :nil]}
  [& arguments]
  (case (first arguments)
    "--worker"
    (worker-main! (second arguments))

    "--prepare-base"
    (let [root (.getCanonicalPath (io/file (second arguments)))]
      (.mkdirs (io/file root))
      (let [expected (.getCanonicalFile (io/file "src/seon/fn.clj"))
            actual (.getCanonicalFile (io/file (.toURI (io/resource "seon/fn.clj"))))]
        (when-not (= expected actual)
          (throw (ex-info "Publication classpath does not name its snapshot."
                          {::expected (str expected) ::actual (str actual)}))))
      ;; A cloned compatible base arrives with the seed's store identity; the
      ;; existing export owner reidentifies the COPY before it is opened.
      ;; `refresh-source!` alone decides whether a named change is safe to
      ;; upsert or requires its complete build.
      (let [changed-paths (some-> (nth arguments 2 nil) edn/read-string)]
        (when changed-paths
          (@export-reidentify! (str (io/file root "data" "store"))))
        (with-bindings {@cluster-source-progress #(println "bin/test: SOURCE" %)}
          (if changed-paths
            (@cluster-refresh-source! root changed-paths)
            (@cluster-refresh-source! root))))
      (let [held-store (store/open-store!
                        {:seon.store/dir (str (io/file root "data" "store"))})]
        (try
          (let [database (source/database held-store
                           (:seon.source/commit-id (source/current held-store)))
                database (vary-meta database assoc :seon.schema/projection
                                    (schema/projection-from-database database))
                captured (provenance database)]
            (when (:seon.error/kind captured)
              (throw (ex-info (:seon.error/message captured) captured)))
            (spit (io/file root "provenance.edn")
                  (pr-str (select-keys captured
                                      [:seon.test.run/program-digest
                                       :seon.test.run/basis-t
                                       :seon.test.run/branch]))))
          (finally (store/release-store! held-store))))
      ;; The completed artifact is authoritative for full AND incremental
      ;; publication; the in-memory analysis cache only covers full builds.
      (let [artifact (edn/read-string (slurp (@cluster-source-artifact-file root)))
            manifest (:seon.fn/manifest artifact)]
        (when-not (seq (:seon.fn.manifest/artifacts manifest))
          (throw (ex-info "Publication produced no program manifest." {::root root})))
        (spit (io/file root "manifest.edn") (pr-str manifest)))
      (println "bin/test: shared published test base ready at" root)
      (shutdown-agents))

    (let [[cluster-name root git-sha selection-mode & namespace-names]
          arguments]
      (System/exit
       (coordinator-main! cluster-name root git-sha selection-mode
                          namespace-names)))))
