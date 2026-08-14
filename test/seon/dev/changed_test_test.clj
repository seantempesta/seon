(ns seon.dev.changed-test-test
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.operator.state :as operator.state]
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
        (operator.state/run-process!
         {:seon.operator.subprocess/argv
          ["bb" "--config" (str root "/bb.edn")
           "--deps-root" root "-e" expression]
          :seon.operator.subprocess/deadline-ms 30000})]
    {:exit (:seon.operator.subprocess/exit result)
     :out (:seon.operator.subprocess/output result)
     :err (:seon.operator.subprocess/error-output result)}))

(deftest changed-paths-are-handed-to-the-one-gate-selector
  (testing "changed-test decides no test selection of its own: it names the
            changed paths and `bin/test --changed` derives the tests they
            reach. A second selector here is exactly the drift this
            delegation removes."
    (let [expression
          (str
           "(require 'seon.dev.changed-test) "
           "(let [calls (atom []) run (ns-resolve 'seon.dev.changed-test "
           "'run-command!) gate (ns-resolve 'seon.dev.changed-test "
           "'run-gate!)] "
           "(prn (with-redefs-fn {run (fn [root boundary command environment] "
           "(swap! calls conj [root boundary command environment]) "
           "{:seon.dev.changed-test/status :passed})} "
           "#(do (gate \"/checkout\" "
           "[\"src/seon/cluster/run.clj\" \"test/seon/fn_test.clj\"]) "
           "@calls))))")
          {:keys [exit out err]} (run-babashka expression)]
      (is (zero? exit) err)
      (is (= [["/checkout" :gate
               ["/checkout/bin/test"
                "--changed" "src/seon/cluster/run.clj"
                "--changed" "test/seon/fn_test.clj"]
               {}]]
             (edn/read-string out))))))

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
               #(operator.state/run-process!
                 {:seon.operator.subprocess/argv
                  ["/usr/bin/python3" "-c" python
                   (.getPath ready-file) (.getPath go-file)
                   (.getPath child-file)]
                  :seon.operator.subprocess/deadline-ms 5000})))]
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
              (is (= :seon.operator.subprocess/deadline-exceeded
                     (:seon.error/kind data)))
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
         #((var-get #'operator.state/run-process!)
            {:seon.operator.subprocess/argv
             ["/a/foreign/process/that/must/not/be-launched"]}))]
    (is (= :seon.operator.subprocess/deadline-undeclared
           (:seon.error/kind (ex-data failure))))))

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
               #(operator.state/run-process!
                 {:seon.operator.subprocess/argv ["/usr/bin/true"]
                  :seon.operator.subprocess/deadline-ms 100})))]
        (is (= :seon.operator.subprocess/deadline-exceeded
               (:seon.error/kind (ex-data failure))))
        (is (= :stdout
               (:seon.operator.subprocess/phase (ex-data failure))))
        (is (true? (:seon.operator.subprocess/reaped? (ex-data failure)))))
      (finally
        (deliver release-capture true)))))

(deftest plain-babashka-cannot-load-the-quarry
  (let [{:keys [exit err]}
        (run-babashka "(require 'seon.time)")]
    (is (pos? exit))
    (is (re-find #"Could not locate seon/time" err))))
