(ns seon.cluster.subprocess-test
  (:require [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.process :as operator.process]
            [seon.test-support :as test-support])
  (:import [java.nio.file FileSystems StandardWatchEventKinds]
           [java.util.concurrent TimeUnit]))

(defn- caught
  [f]
  (try
    (f)
    nil
    (catch Throwable error
      error)))

(defn- await-created!
  [watcher filename]
  (loop []
    (let [watch-key (.poll watcher test-support/event-backstop-seconds
                           TimeUnit/SECONDS)]
      (when-not watch-key
        (throw
         (ex-info "The foreign child did not publish its filesystem event."
                  {:seon.operator.subprocess/expected-file filename})))
      (let [created?
            (some #(= filename (str (.context ^java.nio.file.WatchEvent %)))
                  (.pollEvents watch-key))]
        (.reset watch-key)
        (if created?
          filename
          (recur))))))

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

(deftest foreign-process-deadline-reaps-a-late-descendant
  (let [root (io/file "tmp/changed-test" (str (random-uuid)))
        ready-file (io/file root "parent-ready")
        go-file (io/file root "spawn-child")
        child-file (io/file root "child-pid")
        python
        (str
         "import os,signal,subprocess,sys\n"
         "ready,go,child_pid=sys.argv[1:]\n"
         "os.mkfifo(go)\n"
         "open(ready,'x').close()\n"
         "open(go).read()\n"
         "code=\"import os,signal,sys; signal.signal(signal.SIGTERM, signal.SIG_IGN); tmp=sys.argv[1]+'.tmp'; f=open(tmp,'w'); f.write(str(os.getpid())); f.close(); os.replace(tmp,sys.argv[1]); signal.pause()\"\n"
         "subprocess.Popen([sys.executable,'-c',code,child_pid])\n"
         "signal.pause()\n")]
    (.mkdirs root)
    (with-open [watcher (.newWatchService (FileSystems/getDefault))]
      (.register (.toPath root)
                 watcher
                 (into-array
                  java.nio.file.WatchEvent$Kind
                  [StandardWatchEventKinds/ENTRY_CREATE]))
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
          (await-created! watcher (.getName ready-file))
          (spit go-file "")
          (await-created! watcher (.getName child-file))
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
            (test-support/delete-recursively! root)))))))

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

(deftest plain-babashka-cannot-load-the-quarry
  (let [{:keys [exit err]}
        (run-babashka "(require 'seon.time)")]
    (is (pos? exit))
    (is (re-find #"Could not locate seon/time" err))))
