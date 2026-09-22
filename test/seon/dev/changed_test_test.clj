(ns seon.dev.changed-test-test
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
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
           "[\"src/seon/turn.clj\" \"test/seon/fn_test.clj\"]) "
           "@calls))))")
          {:keys [exit out err]} (run-babashka expression)]
      (is (zero? exit) err)
      (is (= [["/checkout" :gate
               ["/checkout/bin/test"
                "--changed" "src/seon/turn.clj"
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
               #(operator.process/run-process!
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
    (is (= :seon.operator.subprocess/deadline-ms (:seon.operator.subprocess/deadline-member (ex-data failure))))))

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

(deftest plain-babashka-cannot-load-the-quarry
  (let [{:keys [exit err]}
        (run-babashka "(require 'seon.time)")]
    (is (pos? exit))
    (is (re-find #"Could not locate seon/time" err))))

(deftest concurrent-tooling-transition-refuses-busy
  (let [{:keys [exit out err]}
        (run-babashka
         (pr-str
          '(do
             (require 'seon.dev.state 'babashka.fs)
             (let [directory (str (babashka.fs/create-temp-dir {:dir "tmp" :prefix "b1b-tooling-"}))
                   config {:seon.dev.config/process-dir (str (babashka.fs/absolutize directory))}
                   acquired (promise) release (promise)
                   owner (future (seon.dev.state/with-lock config :changed-test 5000
                                   #(do (deliver acquired true)
                                        (when (= :timeout (deref release 5000 :timeout))
                                          (throw (ex-info "Owner release absent" {})))
                                        :released)))]
               (try
                 (when (= :timeout (deref acquired 5000 :timeout))
                   (throw (ex-info "Owner acquisition absent" {})))
                 (let [busy (try (seon.dev.state/with-lock config :changed-test 5000 (constantly :wrong))
                                 (catch clojure.lang.ExceptionInfo e (boolean (:seon.dev.lock/path (ex-data e)))))]
                   (deliver release true)
                   (prn [busy (deref owner 5000 :timeout)
                         (seon.dev.state/with-lock config :changed-test 5000 (constantly :acquired))]))
                 (finally (deliver release true)
                          (try (deref owner 5000 :timeout)
                               (finally (babashka.fs/delete-tree directory)))))))))]
    (is (zero? exit) err)
    (is (= [true :released :acquired] (edn/read-string out)))))
