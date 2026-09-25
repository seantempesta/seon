(ns seon.cluster.subprocess-test
  (:require [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.process :as operator.process]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent TimeUnit]))

(defn- caught
  [f]
  (try
    (f)
    nil
    (catch Throwable error
      error)))

(defn- await-created!
  ;; Polls: macOS's JDK WatchService is a PollingWatchService whose ~2 s latency
  ;; (measured 2009 ms) let the spawn signal race the 2 s deadline.
  [^java.io.File file]
  (let [deadline (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS test-support/event-backstop-seconds))]
    (loop []
      (cond (.exists file) file
            (> (System/nanoTime) deadline)
            (throw (ex-info "The foreign child did not publish its filesystem event."
                            {:seon.operator.subprocess/expected-file (.getName file)}))
            :else (do (Thread/sleep 10) (recur))))))

(defn- run-babashka [expression]
  (let [root (System/getProperty "user.dir")
        result
        (operator.process/run-process!
         {:seon.operator.subprocess/argv
          ["bb" "--config" (str root "/bb.edn")
           "--deps-root" root "-e" expression]
          :seon.operator.subprocess/deadline-ms 30000})]
    {:exit (:seon.operator.subprocess/exit result)
     :out (:seon.operator.subprocess/output result)
     :err (:seon.operator.subprocess/error-output result)}))

(def ^:private late-spawner
  "A python parent that forks one TERM-ignoring child, which records its pid,
  once its `go` file exists. A file, not a fifo: a fifo writer whose reader was
  killed blocks in an uninterruptible open."
  (str
   "import os,signal,subprocess,sys,time\n"
   "ready,go,child_pid=sys.argv[1:]\n"
   "open(ready,'x').close()\n"
   "while not os.path.exists(go): time.sleep(0.001)\n"
   "code=\"import os,signal,sys; signal.signal(signal.SIGTERM, signal.SIG_IGN); tmp=sys.argv[1]+'.tmp'; f=open(tmp,'w'); f.write(str(os.getpid())); f.close(); os.replace(tmp,sys.argv[1]); signal.pause()\"\n"
   "subprocess.Popen([sys.executable,'-c',code,child_pid])\n"
   "signal.pause()\n"))

(defn- alive-pids
  "The pids named by `directory`'s file names that are still alive."
  [^java.io.File directory]
  (filterv #(some-> (java.lang.ProcessHandle/of %) (.orElse nil) (.isAlive))
           (keep #(parse-long (.getName ^java.io.File %)) (.listFiles directory))))

(defn- kill-pids! [pids]
  (doseq [pid pids] (some-> (java.lang.ProcessHandle/of pid) (.orElse nil) (.destroyForcibly))))

(deftest foreign-process-deadline-reaps-a-late-descendant
  (let [root (io/file "tmp/changed-test" (str (random-uuid)))
        ready-file (io/file root "parent-ready")
        go-file (io/file root "spawn-child")
        child-file (io/file root "child-pid")
        python late-spawner]
    (.mkdirs root)
    (let [failure
          (future
            (caught
             #(operator.process/run-process!
               {:seon.operator.subprocess/argv
                ["/usr/bin/python3" "-c" python
                 (.getPath ready-file) (.getPath go-file)
                 (.getPath child-file)]
                ;; The parent and child start in ~100 ms; the deadline plus the
                ;; owner's 1 s TERM grace must fit the test's 5 s bound.
                :seon.operator.subprocess/deadline-ms 2000})))]
      (try
        (await-created! ready-file)
        (spit go-file "")
        (await-created! child-file)
        (let [error (test-support/await-event!
                     failure :foreign-process-deadline)
              data (ex-data error)
              child-pid (parse-long (slurp child-file))
              child (some-> (java.lang.ProcessHandle/of child-pid)
                            (.orElse nil))]
          (try
            (is (keyword? (:seon.operator.subprocess/phase data)))
            (is (= :process-exit
                   (:seon.operator.subprocess/phase data)))
            (is (true? (:seon.operator.subprocess/reaped? data)))
            (is (false? (boolean (and child (.isAlive child))))
                "the deadline reaps a descendant born after process launch")
            (finally
              (when (and child (.isAlive child))
                (.destroyForcibly child)))))
        (finally
          (test-support/delete-recursively! root))))))

(deftest foreign-process-refuses-an-undeclared-deadline-before-launch
  (let [failure
        (caught
         #((var-get #'operator.process/run-process!)
            {:seon.operator.subprocess/argv
             ["/a/foreign/process/that/must/not/be-launched"]}))]
    ;; The armed contract refuses before the body runs, naming the absent
    ;; deadline as its input problem.
    (is (= :seon.fn.arity/input (:seon.error/member (ex-data failure))))
    (is (str/includes? (str (:seon.error/message (ex-data failure)))
                       ":seon.operator.subprocess/deadline-ms"))))

(deftest foreign-process-deadline-covers-output-capture
  (let [release-capture (promise)
        process-record (process/process ["/usr/bin/true"]
                                        {:out :string :err :string})]
    (try
      (let [failure
            (with-redefs
             [process/process
              (fn [& _]
                (assoc process-record :out (future @release-capture)))]
              (caught
               #(operator.process/run-process!
                 {:seon.operator.subprocess/argv ["/usr/bin/true"]
                  :seon.operator.subprocess/deadline-ms 100})))]
        (is (keyword? (:seon.operator.subprocess/phase (ex-data failure))))
        (is (= :stdout
               (:seon.operator.subprocess/phase (ex-data failure))))
        (is (true? (:seon.operator.subprocess/reaped? (ex-data failure)))))
      (finally
        (deliver release-capture true)))))

(deftest foreign-process-joins-its-output-observer-on-every-exit
  ;; A timed-out child's observer is joined before the refusal; one outliving
  ;; that join is named, and an observer's own failure is the refusal's cause.
  (let [finished (atom nil)
        refusal (fn [pause-ms]
                  (ex-data (caught #(operator.process/run-process!
                                     {:seon.operator.subprocess/argv ["/bin/sh" "-c" "echo a; sleep 5"]
                                      :seon.operator.subprocess/deadline-ms 300
                                      :seon.operator.subprocess/observe-output!
                                      (fn [_] (Thread/sleep (long pause-ms)) (reset! finished pause-ms) nil)}))))
        joined (refusal 600)]
    (is (= 600 @finished) "the refusal follows the observer's exit")
    (is (= :process-exit (:seon.operator.subprocess/phase joined)))
    (is (nil? (:seon.operator.subprocess/unjoined joined)))
    (is (= [:stdout] (:seon.operator.subprocess/unjoined (refusal 2500))))
    (is (= "observer refused"
           (ex-message (ex-cause (caught #(operator.process/run-process!
                                          {:seon.operator.subprocess/argv ["/bin/echo" "a"]
                                           :seon.operator.subprocess/deadline-ms 2000
                                           :seon.operator.subprocess/observe-output!
                                           (fn [_] (throw (ex-info "observer refused" {})))}))))))))

(deftest a-child-forked-as-termination-begins-never-escapes
  ;; The parent forks when signalled just before, at, or after the 500 ms deadline:
  ;; the tree is frozen before any member can die, so no child outlives the refusal.
  (doseq [signal-ms [490 500 505 510]]
    (let [root (io/file "tmp/changed-test" (str (random-uuid)))
          started (System/nanoTime)
          failure (future
                    (caught
                     #(operator.process/run-process!
                       {:seon.operator.subprocess/argv
                        ["/usr/bin/python3" "-c" late-spawner (.getPath (io/file root "ready"))
                         (.getPath (io/file root "go")) (.getPath (io/file root "pid"))]
                        :seon.operator.subprocess/deadline-ms 500})))]
      (.mkdirs root)
      (try
        (await-created! (io/file root "ready"))
        (Thread/sleep (max 0 (- signal-ms (quot (- (System/nanoTime) started) 1000000))))
        (spit (io/file root "go") "")
        (let [data (ex-data (test-support/await-event! failure :foreign-process-deadline))
              pid (some-> (io/file root "pid") (#(when (.exists ^java.io.File %) (slurp %))) parse-long)
              child (some-> pid java.lang.ProcessHandle/of (.orElse nil))]
          (try
            (is (true? (:seon.operator.subprocess/reaped? data)) (str signal-ms " ms"))
            (is (false? (boolean (and child (.isAlive child)))) (str signal-ms " ms: the child escaped"))
            (finally (when (and child (.isAlive child)) (.destroyForcibly child)))))
        (finally (test-support/delete-recursively! root))))))

(deftest a-tree-forking-through-termination-leaves-no-live-member
  ;; The root ignores TERM and forks a TERM-ignoring member every 10 ms, each
  ;; recording its pid, so members are born through the whole TERM grace; a
  ;; walk taken once, before TERM, misses them. Freezing to a fixpoint ends all.
  (let [root (io/file "tmp/changed-test" (str (random-uuid)))
        script (str "trap '' TERM; i=0; while [ $i -lt 300 ]; do /bin/sh -c 'trap \"\" TERM; : > \"$0/$$\"; exec /bin/sleep 30' '"
                    (.getPath root) "' & i=$((i+1)); /bin/sleep 0.01; done; wait")]
    (.mkdirs root)
    (try
      (let [data (ex-data (caught #(operator.process/run-process!
                                    {:seon.operator.subprocess/argv ["/bin/sh" "-c" script]
                                     :seon.operator.subprocess/deadline-ms 300})))]
        (is (= :process-exit (:seon.operator.subprocess/phase data)))
        (is (true? (:seon.operator.subprocess/reaped? data)))
        (is (seq (.listFiles root)) "members were born before the deadline")
        (is (= [] (alive-pids root)) "no member outlived the refusal"))
      (finally
        (kill-pids! (alive-pids root))
        (test-support/delete-recursively! root)))))

(deftest plain-babashka-cannot-load-the-quarry
  (let [{:keys [exit err]}
        (run-babashka "(require 'seon.time)")]
    (is (pos? exit))
    (is (re-find #"Could not locate seon/time" err))))
