(require '[clojure.java.io :as io]
         '[seon.test.runner :as runner])
(import '(java.io PrintWriter)
        '(java.nio.file FileSystems StandardWatchEventKinds WatchEvent$Kind)
        '(java.util.concurrent TimeUnit))

(let [root (doto (io/file "tmp" "runner-exchange-before") .mkdirs)
      accepted (io/file root "accepted")
      child-pid-file (io/file root "child-pid")
      stderr (io/file root "stderr.log")
      _ (doseq [file [accepted child-pid-file stderr]]
          (io/delete-file file true))
      watcher (.newWatchService (FileSystems/getDefault))
      _ (.register (.toPath root)
                   watcher
                   (into-array WatchEvent$Kind
                               [StandardWatchEventKinds/ENTRY_CREATE]))
      process (.start
               (doto
                (ProcessBuilder.
                 ^java.util.List
                 ["/usr/bin/python3"
                  "tmp/runner-exchange-worker.py"
                  (.getCanonicalPath accepted)
                  (.getCanonicalPath child-pid-file)])
                (.redirectError stderr)))
      worker {::runner/worker-id "scratch-before"
              ::runner/worker-process process
              ::runner/worker-reader (io/reader (.getInputStream process))
              ::runner/worker-writer (PrintWriter. (.getOutputStream process) true)
              ::runner/worker-error-log (.getCanonicalPath stderr)}]
  (try
    (println {:before/ready (#'runner/read-worker-protocol! worker)})
    (let [rpc (future
                (#'runner/worker-rpc!
                 worker
                 {::runner/worker-command :run
                  ::runner/worker-task
                  {::runner/task-id "scratch-mid-task"
                   ::runner/task-symbols ["scratch/mid-task"]}}))
          watch-key (.poll watcher 5 TimeUnit/SECONDS)]
      (when-not watch-key
        (throw (ex-info "Worker never published command acceptance." {})))
      (.pollEvents watch-key)
      (.reset watch-key)
      (let [child-pid (Long/parseLong (.trim (slurp child-pid-file)))
            child (.orElse (java.lang.ProcessHandle/of child-pid) nil)]
        (println {:before/descendant-pid child-pid
                  :before/descendant-alive-before-kill
                  (boolean (and child (.isAlive child)))}))
      (.destroyForcibly process)
      (.get (.onExit (.toHandle process)) 5 TimeUnit/SECONDS)
      (let [rpc-result
            (try
              (deref rpc 2000 ::wedged)
              (catch Throwable failure
                {:before/failure-class (.getName (class failure))
                 :before/failure-message (ex-message failure)
                 :before/failure-data (ex-data failure)}))]
        (println {:before/worker-exit (.exitValue process)
                  :before/rpc rpc-result
                  :before/worker-alive (.isAlive process)})))
    (finally
      (when (.exists child-pid-file)
        (let [child-pid (Long/parseLong (.trim (slurp child-pid-file)))
              child (.orElse (java.lang.ProcessHandle/of child-pid) nil)]
          (when (and child (.isAlive child))
            (.destroyForcibly child)
            (.get (.onExit child) 5 TimeUnit/SECONDS))))
      (.close watcher))))
