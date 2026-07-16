(ns seon.dev.process-test
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [malli.core :as m]
            [seon.dev.artifact :as artifact]
            [seon.dev.config :as dev-config]
            [seon.dev.process :as process]
            [seon.dev.state :as state]
            [seon.launch :as launch])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net ServerSocket SocketException]
           [java.util.concurrent TimeUnit]))

(defn- test-config []
  (let [directory (fs/create-temp-dir {:prefix "seon-operator-test-"})]
    {:seon.dev.config/root (str (fs/normalize
                                  (fs/absolutize (System/getProperty "user.dir"))))
     :seon.dev.config/process-dir (str (fs/path directory "process"))
     :seon.dev.config/log-dir (str (fs/path directory "logs"))
     :seon.dev.config/environment (into {} (System/getenv))
     :seon.dev.test/directory (str directory)}))

(defn- operator-config []
  (dev-config/load! (System/getProperty "user.dir")))

(defn- harmless-spec [id argv]
  {:seon.dev.process/id id
   :seon.dev.process/argv argv
   :seon.dev.process/environment (into {} (System/getenv))
   :seon.dev.process/dependencies []
   :seon.dev.process/readiness :seon.dev.process.readiness/process
   :seon.dev.process/ready-timeout-ms 5000
   :seon.dev.process/shutdown-grace-ms 100
   :seon.dev.process/artifact-digest "test-artifact"})

(defn- executable! [path]
  (spit (str path) "#!/bin/sh\nexit 0\n")
  (fs/set-posix-file-permissions path "rwxr-xr-x")
  path)

(defn- live-probe-record [configuration record]
  (let [pid (.pid (java.lang.ProcessHandle/current))
        start (state/process-start-instant pid)
        generation (random-uuid)
        adoption
        (str (fs/path (:seon.dev.test/directory configuration)
                      (str generation ".adopted")))]
    (spit adoption
          (str "{\"generation\":\"" generation
               "\",\"status\":\"adopted\"}"))
    (assoc record
           :seon.dev.process/containment
           {:seon.dev.process.containment/generation generation
            :seon.dev.process.containment/owner-pid pid
            :seon.dev.process.containment/owner-start-instant start
            :seon.dev.process.containment/anchor-pid pid
            :seon.dev.process.containment/anchor-start-instant start
            :seon.dev.process.containment/process-group pid
            :seon.dev.process.containment/workload-pid pid
            :seon.dev.process.containment/workload-start-instant start
            :seon.dev.process.containment/shutdown-grace-ms 100
            :seon.dev.process.containment/control-socket
            (str (fs/path (:seon.dev.test/directory configuration)
                          (str generation ".sock")))
            :seon.dev.process.containment/adoption-path adoption
            :seon.dev.process.containment/result-path
            (str (fs/path (:seon.dev.test/directory configuration)
                          (str generation ".result")))})))

(defn- hard-clean-containment-fixture! [record]
  (when-let [containment (:seon.dev.process/containment record)]
    (let [identity
          (fn [role]
            {:seon.dev.process/pid
             (get containment (keyword "seon.dev.process.containment"
                                       (str (name role) "-pid")))
             :seon.dev.process/start-instant
             (get containment (keyword "seon.dev.process.containment"
                                       (str (name role) "-start-instant")))})
          anchor (identity :anchor)
          owner (identity :owner)
          workload (identity :workload)]
      (when (state/process-identity-alive? anchor)
        (shell/sh {:continue true
                   :cmd ["/bin/kill" "-KILL" "--"
                         (str "-" (:seon.dev.process/pid anchor))]}))
      (when (state/process-identity-alive? owner)
        (shell/sh {:continue true
                   :cmd ["/bin/kill" "-KILL"
                         (str (:seon.dev.process/pid owner))]}))
      (when (state/process-identity-alive? workload)
        (shell/sh {:continue true
                   :cmd ["/bin/kill" "-KILL"
                         (str (:seon.dev.process/pid workload))]}))
      (loop [remaining 500]
        (when (and (pos? remaining)
                   (some state/process-identity-alive?
                         (map identity [:owner :anchor :workload])))
          (Thread/sleep 10)
          (recur (dec remaining))))
      (let [alive (mapv (comp boolean state/process-identity-alive? identity)
                        [:owner :anchor :workload])]
        (when-let [path
                   (:seon.dev.process.containment/control-socket containment)]
          (fs/delete-if-exists path))
        alive))))

(deftest legacy-containment-grace-is-derived-only-while-reading
  (let [configuration (test-config)
        id :seon.dev.process/legacy-containment-grace
        pid (.pid (java.lang.ProcessHandle/current))
        start (state/process-start-instant pid)
        record
        (live-probe-record
         configuration
         {:seon.dev.process/id id
          :seon.dev.process/pid pid
          :seon.dev.process/start-instant start
          :seon.dev.process/process-group pid
          :seon.dev.process/argv ["legacy"]
          :seon.dev.process/environment-digest (apply str (repeat 64 "0"))
          :seon.dev.process/artifact-digest "legacy"
          :seon.dev.process/target :seon.dev.target/development
          :seon.dev.process/started-at "legacy"
          :seon.dev.process/log
          (str (fs/path (:seon.dev.test/directory configuration)
                        "legacy.log"))})
        grace-key :seon.dev.process.containment/shutdown-grace-ms
        legacy-record (update record :seon.dev.process/containment
                              dissoc grace-key)
        record-path (fs/path (:seon.dev.config/process-dir configuration)
                             "processes/legacy-containment-grace.edn")]
    (try
      (state/write-edn! record-path legacy-record)
      (let [decoded (process/read-process configuration id)
            containment (:seon.dev.process/containment decoded)
            terminal
            {:generation
             (str (:seon.dev.process.containment/generation containment))
             :status "drained"
             :anchor_exit -9}]
        (is (= 2500 (get containment grace-key)))
        (is (#'process/matching-terminal? containment terminal))
        (is (not (contains? (#'process/normalized-terminal
                             containment terminal)
                            :seon.dev.process.containment/trigger))
            "historical absence remains visible in returned evidence"))
      (is (thrown? clojure.lang.ExceptionInfo
                   (#'process/write-process! configuration id legacy-record))
          "new publications remain strict")
      (finally
        (fs/delete-tree (:seon.dev.test/directory configuration)
                        {:force true})))))

(defn- with-default-launch-descriptor [target]
  (dev-config/select-launch-descriptor
   target
   (launch/default-descriptor
    {::launch/cluster-dir (:seon.dev.config/cluster-dir target)
     ::launch/artifact-flavor
     (:seon.dev.config/artifact-flavor target)
     ::launch/client-build-id
     (:seon.dev.config/client-build-id target)
     ::launch/execution-build-id
     (:seon.dev.config/execution-build-id target)
     ::launch/execution-output
     (:seon.dev.config/execution-output target)
     ::launch/request-socket-path
     (:seon.dev.config/request-socket target)
     ::launch/writer-repl-port-file
     (:seon.dev.config/writer-repl-port-file target)
     ::launch/process-dir (:seon.dev.config/process-dir target)
     ::launch/log-dir (:seon.dev.config/log-dir target)
     ::launch/http-port (:seon.dev.config/http-port target)
     ::launch/http-port-file
     (:seon.dev.config/http-port-file target)})))

(defn- target-config [configuration directory]
  (with-default-launch-descriptor
    (merge configuration
               {:seon.dev.config/client-build-id "client"
                :seon.dev.config/execution-build-id "execution"
                :seon.dev.config/artifact-flavor
                :seon.dev.artifact.flavor/default
                :seon.dev.config/source-checkout? true
                :seon.dev.config/shadow-cache-root
                (str (fs/path directory "shadow"))
                :seon.dev.config/client-output
                (str (fs/path directory "client.js"))
                :seon.dev.config/execution-output
                (str (fs/path directory "execution.js"))
                :seon.dev.config/writer-output
                (str (fs/path directory "writer.jar"))
                :seon.dev.config/artifact-manifest
                (str (fs/path directory "artifact.edn"))
                :seon.dev.config/cluster-dir
                (str (fs/path directory "test"))
                :seon.dev.config/cluster-name "test"
                :seon.dev.config/request-socket
                (str (fs/path directory "req.sock"))
                :seon.dev.config/writer-repl-port 0
                :seon.dev.config/writer-repl-port-file
                (str (fs/path directory "writer-port"))
                :seon.dev.config/http-port 0
                :seon.dev.config/http-port-file
                (str (fs/path directory "pod-port"))})))

(def target-manifest
  {:seon.dev.artifact/flavor :seon.dev.artifact.flavor/default
   :seon.dev.artifact/client-build-id "client"
   :seon.dev.artifact/execution-build-id "execution"
   :seon.dev.artifact/client-digest (apply str (repeat 64 "c"))
   :seon.dev.artifact/execution-digest (apply str (repeat 64 "e"))
   :seon.dev.artifact/writer-digest "writer"
   :seon.dev.artifact/application-digest "application"})

(defn- cleanup! [configuration ids]
  (doseq [id ids]
    (try (process/stop! configuration id) (catch Throwable _)))
  (fs/delete-tree (:seon.dev.test/directory configuration)))

(def ^:private writer-fixture-source
  (str "import os,socket,sys,time\n"
       "req,port,delay=sys.argv[1],sys.argv[2],float(sys.argv[3])\n"
       "time.sleep(delay)\n"
       "try: os.unlink(req)\n"
       "except FileNotFoundError: pass\n"
       "s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)\n"
       "s.bind(req); s.listen(16)\n"
       "open(port,'w').write('1')\n"
       "while True:\n"
       " c,_=s.accept(); c.close()\n"))

(def ^:private pod-fixture-source
  (str "import http.server,sys,time\n"
       "port_file,delay=sys.argv[1],float(sys.argv[2])\n"
       "time.sleep(delay)\n"
       "class H(http.server.BaseHTTPRequestHandler):\n"
       " def do_GET(self):\n"
       "  self.send_response(200); self.end_headers(); self.wfile.write(b'{}')\n"
       " def log_message(self,*args): pass\n"
       "server=http.server.HTTPServer(('127.0.0.1',0),H)\n"
       "open(port_file,'w').write(str(server.server_port))\n"
       "server.serve_forever()\n"))

(defn- signal-fixture-config [directory]
  (let [root (str (fs/normalize
                   (fs/absolutize (System/getProperty "user.dir"))))
        base (target-config
              {:seon.dev.config/root root
               :seon.dev.config/process-dir (str (fs/path directory "process"))
               :seon.dev.config/log-dir (str (fs/path directory "logs"))
               :seon.dev.config/environment (into {} (System/getenv))
               :seon.dev.test/directory directory}
              directory)
        output (fs/path directory "client.js")
        runtime (fs/path directory "shadow/builds/client/dev/out/cljs-runtime/a.js")]
    (fs/create-dirs (fs/parent runtime))
    (spit (str output) "client")
    (spit (str runtime) "runtime")
    base))

(defn- contained-one-shot-request
  [configuration argv application-result-path timeout-ms artifact-digest]
  {:seon.dev.process/configuration configuration
   :seon.dev.process/argv argv
   :seon.dev.process/environment (into {} (System/getenv))
   :seon.dev.process/artifact-digest artifact-digest
   :seon.dev.process/application-result-path application-result-path
   :seon.dev.process/timeout-ms timeout-ms
   :seon.dev.process/shutdown-grace-ms 100})

(defn- containment-identities [record]
  (let [containment (:seon.dev.process/containment record)]
    (mapv #(hash-map
            :seon.dev.process/pid
            (get containment
                 (keyword "seon.dev.process.containment"
                          (str (name %) "-pid")))
            :seon.dev.process/start-instant
            (get containment
                 (keyword "seon.dev.process.containment"
                          (str (name %) "-start-instant"))))
          [:owner :anchor :workload])))

(deftest contained-one-shot-publishes-identity-before-workload-admission
  (let [directory (str (fs/create-temp-dir {:prefix "seon-one-shot-gate-"}))
        configuration (signal-fixture-config directory)
        marker (str (fs/path directory "executed"))
        application-result (str (fs/path directory "application.edn"))
        request
        (contained-one-shot-request
         configuration
         ["python3" "-c"
          "import pathlib,sys; pathlib.Path(sys.argv[1]).write_text('ran'); pathlib.Path(sys.argv[2]).write_text('{:ok true}')"
          marker application-result]
         application-result 5000 "gate-proof")
        adopt @#'process/adopt-containment!
        observed-record (atom nil)]
    (try
      (let [result
            (with-redefs-fn
              {#'process/adopt-containment!
               (fn [record]
                 (reset! observed-record
                         (process/read-process configuration
                                               process/restore-admin-id))
                 (is (= record @observed-record)
                     "the exact identity record is durable before admission")
                 (is (not (fs/exists? marker))
                     "the gated workload has not executed before adoption")
                 (adopt record))}
              #(process/contained-one-shot! request))]
        (is (m/validate process/contained-one-shot-result-schema result))
        (is (false? (:seon.dev.process/timed-out? result)))
        (is (= :seon.dev.process.containment.trigger/workload-exit
               (get-in result [:seon.dev.process/terminal
                               :seon.dev.process.containment/trigger])))
        (is (fs/regular-file? marker))
        (is (= "{:ok true}" (slurp application-result)))
        (is (nil? (process/read-process configuration process/restore-admin-id)))
        (is (not-any? state/process-identity-alive?
                      (containment-identities @observed-record))))
      (finally
        (when-let [record (or (process/read-process configuration
                                                    process/restore-admin-id)
                              @observed-record)]
          (hard-clean-containment-fixture! record))
        (fs/delete-tree directory {:force true})))))

(deftest contained-one-shot-resumes-the-exact-adopted-generation
  (let [directory (str (fs/create-temp-dir {:prefix "seon-one-shot-resume-"}))
        configuration (signal-fixture-config directory)
        executions (str (fs/path directory "executions"))
        application-result (str (fs/path directory "application.edn"))
        request
        (contained-one-shot-request
         configuration
         ["python3" "-c"
          (str "import pathlib,sys,time\n"
               "p=pathlib.Path(sys.argv[1]); p.write_text((p.read_text() if p.exists() else '')+'x')\n"
               "time.sleep(.2)\n"
               "pathlib.Path(sys.argv[2]).write_text('{:ok true}')\n")
          executions application-result]
         application-result 5000 "resume-proof")
        spec (#'process/contained-one-shot-spec request)
        record (atom nil)]
    (try
      (reset! record
              (#'process/spawn-detached!
               configuration spec
               {:seon.dev.process/gated? true
                :seon.dev.process/application-result-path application-result}))
      (let [result (process/contained-one-shot! request)]
        (is (false? (:seon.dev.process/timed-out? result)))
        (is (= "x" (slurp executions))
            "resumption adopts the retained generation instead of spawning again")
        (is (nil? (process/read-process configuration process/restore-admin-id)))
        (is (not-any? state/process-identity-alive?
                      (containment-identities @record))))
      (finally
        (when-let [retained (process/read-process configuration
                                                  process/restore-admin-id)]
          (hard-clean-containment-fixture! retained))
        (when @record (hard-clean-containment-fixture! @record))
        (fs/delete-tree directory {:force true})))))

(deftest contained-one-shot-timeout-hard-drains-before-cleanup
  (let [directory (str (fs/create-temp-dir {:prefix "seon-one-shot-timeout-"}))
        configuration (signal-fixture-config directory)
        application-result (str (fs/path directory "application.edn"))
        request
        (contained-one-shot-request
         configuration
         ["python3" "-c"
          "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(300)"]
         application-result 100 "timeout-proof")
        cleanup @#'process/cleanup-contained-one-shot!
        cleaned-record (atom nil)]
    (try
      (let [result
            (with-redefs-fn
              {#'process/cleanup-contained-one-shot!
               (fn [selected record]
                 (reset! cleaned-record record)
                 (is (not-any? state/process-identity-alive?
                               (containment-identities record))
                     "cleanup runs only after the complete subtree is absent")
                 (cleanup selected record))}
              #(process/contained-one-shot! request))]
        (is (true? (:seon.dev.process/timed-out? result)))
        (is (= :seon.dev.process.containment.trigger/requested
               (get-in result [:seon.dev.process/terminal
                               :seon.dev.process.containment/trigger])))
        (is (some? @cleaned-record))
        (is (nil? (process/read-process configuration process/restore-admin-id))))
      (finally
        (when-let [record (process/read-process configuration
                                                process/restore-admin-id)]
          (hard-clean-containment-fixture! record))
        (fs/delete-tree directory {:force true})))))

(deftest contained-one-shot-drains-a-foreign-generation-without-overlap
  (let [directory (str (fs/create-temp-dir {:prefix "seon-one-shot-foreign-"}))
        configuration (signal-fixture-config directory)
        application-result (str (fs/path directory "application.edn"))
        foreign-request
        (contained-one-shot-request
         configuration ["sleep" "300"] application-result 5000 "foreign")
        requested
        (contained-one-shot-request
         configuration ["python3" "-c" "raise SystemExit(0)"]
         application-result 5000 "requested")
        foreign (atom nil)]
    (try
      (reset! foreign
              (#'process/spawn-detached!
               configuration (#'process/contained-one-shot-spec foreign-request)
               {:seon.dev.process/gated? true
                :seon.dev.process/application-result-path application-result}))
      (let [error
            (try
              (process/contained-one-shot! requested)
              nil
              (catch clojure.lang.ExceptionInfo failure failure))]
        (is (some? error))
        (is (= :seon.dev.process.status/foreign-one-shot
               (:seon.dev.process/status (ex-data error))))
        (is (nil? (process/read-process configuration process/restore-admin-id)))
        (is (not-any? state/process-identity-alive?
                      (containment-identities @foreign))))
      (finally
        (when-let [record (process/read-process configuration
                                                process/restore-admin-id)]
          (hard-clean-containment-fixture! record))
        (when @foreign (hard-clean-containment-fixture! @foreign))
        (fs/delete-tree directory {:force true})))))

(defn- restore-admin-probe-record
  [configuration]
  (let [pid (.pid (java.lang.ProcessHandle/current))]
    (live-probe-record
     configuration
     {:seon.dev.process/id process/restore-admin-id
      :seon.dev.process/pid pid
      :seon.dev.process/start-instant (state/process-start-instant pid)
      :seon.dev.process/process-group pid
      :seon.dev.process/argv ["restore-admin-probe"]
      :seon.dev.process/environment-digest (apply str (repeat 64 "0"))
      :seon.dev.process/artifact-digest "restore-admin-probe"
      :seon.dev.process/target :seon.dev.target/development
      :seon.dev.process/started-at "restore-admin-probe"
      :seon.dev.process/log
      (str (fs/path (:seon.dev.test/directory configuration)
                    "restore-admin-probe.log"))})))

(deftest restore-admin-absence-is-an-observational-fail-closed-projection
  (let [directory (str (fs/create-temp-dir {:prefix "seon-admin-absence-"}))
        configuration (signal-fixture-config directory)
        request {:seon.dev.process/configuration configuration
                 :seon.dev.process/lock-timeout-ms 1000}
        live (restore-admin-probe-record configuration)
        containment (:seon.dev.process/containment live)
        adoption (:seon.dev.process.containment/adoption-path containment)
        terminal (:seon.dev.process.containment/result-path containment)
        dead-pid 99999999
        dead-start "1970-01-01T00:00:00Z"
        dead
        (-> live
            (assoc :seon.dev.process/pid dead-pid
                   :seon.dev.process/start-instant dead-start
                   :seon.dev.process/process-group dead-pid)
            (assoc-in [:seon.dev.process/containment
                       :seon.dev.process.containment/owner-pid] dead-pid)
            (assoc-in [:seon.dev.process/containment
                       :seon.dev.process.containment/owner-start-instant]
                      dead-start)
            (assoc-in [:seon.dev.process/containment
                       :seon.dev.process.containment/anchor-pid] dead-pid)
            (assoc-in [:seon.dev.process/containment
                       :seon.dev.process.containment/anchor-start-instant]
                      dead-start)
            (assoc-in [:seon.dev.process/containment
                       :seon.dev.process.containment/process-group] dead-pid)
            (assoc-in [:seon.dev.process/containment
                       :seon.dev.process.containment/workload-pid] dead-pid)
            (assoc-in [:seon.dev.process/containment
                       :seon.dev.process.containment/workload-start-instant]
                      dead-start))
        observe
        (fn [record]
          (with-redefs [process/read-process (fn [_ _] record)]
            (process/restore-admin-absence! request)))]
    (try
      (let [absent (observe nil)]
        (is (m/validate process/restore-admin-absence-result-schema absent))
        (is (= :seon.dev.process.status/absent
               (:seon.dev.process/status absent)))
        (is (true? (:seon.dev.process/absent? absent))))
      (let [present (observe live)]
        (is (m/validate process/restore-admin-absence-result-schema present))
        (is (= :seon.dev.process.status/alive
               (:seon.dev.process/status present)))
        (is (false? (:seon.dev.process/absent? present)))
        (is (= (:seon.dev.process.containment/generation containment)
               (:seon.dev.process.containment/generation present))))
      (fs/delete-if-exists adoption)
      (is (= :seon.dev.process.status/adoptable
             (:seon.dev.process/status (observe live))))
      (spit terminal
            (json/generate-string
             {:generation
              (str (:seon.dev.process.containment/generation containment))
              :status "drained"
              :trigger "workload-exit"
              :anchor_exit -9}))
      (let [drained (observe dead)]
        (is (= :seon.dev.process.status/drained
               (:seon.dev.process/status drained)))
        (is (false? (:seon.dev.process/absent? drained)))
        (is (= :seon.dev.process.containment.trigger/workload-exit
               (get-in drained [:seon.dev.process/terminal
                                :seon.dev.process.containment/trigger]))))
      (is (= :seon.dev.process.status/foreign-one-shot
             (:seon.dev.process/status
              (observe (assoc live :seon.dev.process/id process/pod-id)))))
      (fs/delete-if-exists terminal)
      (is (= :seon.dev.process.status/containment-uncertain
             (:seon.dev.process/status (observe dead))))
      (finally
        (fs/delete-tree directory {:force true})))))

(deftest restore-admin-absence-returns-typed-uncertainty-for-unreadable-state
  (let [directory (str (fs/create-temp-dir {:prefix "seon-admin-uncertain-"}))
        configuration (signal-fixture-config directory)
        result
        (with-redefs [process/read-process
                      (fn [& _] (throw (ex-info "invalid record" {})))]
          (process/restore-admin-absence!
           {:seon.dev.process/configuration configuration
            :seon.dev.process/lock-timeout-ms 1000}))]
    (try
      (is (m/validate process/restore-admin-absence-result-schema result))
      (is (= :seon.dev.process.status/containment-uncertain
             (:seon.dev.process/status result)))
      (is (false? (:seon.dev.process/absent? result)))
      (finally
        (fs/delete-tree directory {:force true})))))

(defn- signal-fixture-specs [configuration cut]
  (let [delay #(if (= cut %) "300" "0")
        watcher-digest (artifact/current-client-digest configuration)]
    {process/watcher-id
     {:seon.dev.process/id process/watcher-id
      :seon.dev.process/argv
      ["python3" "-c"
       (str "import time\n"
            "time.sleep(" (delay process/watcher-id) ")\n"
            "print('[:client] Build completed.',flush=True)\n"
            "print('[:execution] Build completed.',flush=True)\n"
            "print('[:test] Build completed.',flush=True)\n"
            "time.sleep(300)\n")]
      :seon.dev.process/environment (into {} (System/getenv))
      :seon.dev.process/dependencies []
      :seon.dev.process/readiness :seon.dev.process.readiness/watcher
      :seon.dev.process/ready-timeout-ms 310000
      :seon.dev.process/shutdown-grace-ms 100
      :seon.dev.process/artifact-digest watcher-digest}
     process/writer-id
     {:seon.dev.process/id process/writer-id
      :seon.dev.process/argv
      ["python3" "-c" writer-fixture-source
       (:seon.dev.config/request-socket configuration)
       (:seon.dev.config/writer-repl-port-file configuration)
       (delay process/writer-id)]
      :seon.dev.process/environment (into {} (System/getenv))
      :seon.dev.process/dependencies []
      :seon.dev.process/readiness :seon.dev.process.readiness/writer
      :seon.dev.process/ready-timeout-ms 310000
      :seon.dev.process/shutdown-grace-ms 100
      :seon.dev.process/artifact-digest "writer"}
     process/pod-id
     {:seon.dev.process/id process/pod-id
      :seon.dev.process/argv
      ["python3" "-c" pod-fixture-source
       (:seon.dev.config/http-port-file configuration)
       (delay process/pod-id)]
      :seon.dev.process/environment (into {} (System/getenv))
      :seon.dev.process/dependencies [process/watcher-id process/writer-id]
      :seon.dev.process/readiness :seon.dev.process.readiness/pod
      :seon.dev.process/ready-timeout-ms 310000
      :seon.dev.process/shutdown-grace-ms 100
      :seon.dev.process/artifact-digest "pod"}}))

(defn run-signal-fixture!
  "Run the real detached startup fixture until its owner is signalled."
  [directory cut reuse-writer?]
  (let [configuration (signal-fixture-config directory)
        specs (signal-fixture-specs configuration cut)]
    (when reuse-writer?
      (process/ensure! configuration (get specs process/writer-id)))
    (process/with-startup-ownership
     configuration
     (fn [start-owned!]
       (doseq [id (process/start-order specs)]
         (process/ensure! configuration (get specs id) start-owned!))))))

(defn run-pre-spawn-signal-fixture!
  "Hold the real spawn phase so SIGINT races managed-record publication."
  [directory]
  (let [configuration (signal-fixture-config directory)
        marker (fs/path directory "spawn-entered")
        pid-file (fs/path directory "spawned-pid")
        spec (harmless-spec process/watcher-id ["sleep" "300"])
        spawn @#'process/spawn-detached!]
    (with-redefs-fn
      {#'process/spawn-detached!
       (fn [selected selected-spec]
         (spit (str marker) "entered")
         (Thread/sleep 500)
         (let [record (spawn selected selected-spec)]
           (spit (str pid-file) (str (:seon.dev.process/pid record)))
           record))}
      (fn []
        (process/with-startup-ownership
         configuration
         #(process/ensure! configuration spec %))))))

(defn- await-record [configuration id]
  (loop [remaining 500]
    (or (process/read-process configuration id)
        (when (pos? remaining)
          (Thread/sleep 20)
          (recur (dec remaining))))))

(defn- signal-owner! [root expression]
  (shell/process
   {:out :string :err :string
    :cmd ["bb" "--config" (str (fs/path root "bb.edn"))
          "--deps-root" root "-e" expression]}))

(deftest real-sigint-cannot-cross-the-spawn-publication-phase
  (let [directory (str (fs/create-temp-dir {:prefix "seon-sigint-spawn-"}))
        configuration (signal-fixture-config directory)
        root (:seon.dev.config/root configuration)
        marker (fs/path directory "spawn-entered")
        pid-file (fs/path directory "spawned-pid")
        expression
        (str "(require '[seon.dev.process-test :as t]) "
             "(t/run-pre-spawn-signal-fixture! " (pr-str directory) ")")
        owner (signal-owner! root expression)]
    (try
      (loop [remaining 500]
        (when (and (pos? remaining) (not (fs/regular-file? marker)))
          (Thread/sleep 10)
          (recur (dec remaining))))
      (is (fs/regular-file? marker) "the spawn phase was entered")
      (shell/sh {:cmd ["/bin/kill" "-INT"
                       (str (.pid ^java.lang.Process (:proc owner)))]})
      (is (.waitFor ^java.lang.Process (:proc owner) 10 TimeUnit/SECONDS))
      (let [result @owner
            spawned-pid
            (when (fs/regular-file? pid-file)
              (some-> (slurp (str pid-file)) str/trim parse-long))]
        (is (= 130 (:exit result)))
        (is (or (nil? spawned-pid)
                (and (pos-int? spawned-pid)
                     (nil? (state/process-start-instant spawned-pid))))
            "shutdown either closes spawn admission or drains its publication")
        (is (nil? (process/read-process configuration process/watcher-id))))
      (finally
        (try (process/stop! configuration process/watcher-id)
             (catch Throwable _))
        (fs/delete-tree directory {:force true})))))

(deftest real-sigint-unwinds-each-ordinary-readiness-cut
  (doseq [cut [process/watcher-id process/writer-id process/pod-id]]
    (let [directory (str (fs/create-temp-dir {:prefix "seon-sigint-ready-"}))
          configuration (signal-fixture-config directory)
          root (:seon.dev.config/root configuration)
          expression
          (str "(require '[seon.dev.process-test :as t]) "
               "(t/run-signal-fixture! " (pr-str directory) " "
               (pr-str cut) " false)")
          owner (signal-owner! root expression)]
      (try
        (let [cut-record (await-record configuration cut)]
          (is (some? cut-record) (str (name cut) " published its identity"))
          (shell/sh {:cmd ["/bin/kill" "-INT"
                           (str (.pid ^java.lang.Process (:proc owner)))]})
          (is (.waitFor ^java.lang.Process (:proc owner) 10 TimeUnit/SECONDS))
          (is (= 130 (:exit @owner)))
          (doseq [id process/target-processes]
            (is (nil? (process/read-process configuration id))
                (str (name cut) " unwind clears " (name id))))
          (is (not (state/process-identity-alive? cut-record))
              (str (name cut) " cannot survive under PID 1")))
        (finally
          (doseq [id (reverse process/target-processes)]
            (try (process/stop! configuration id) (catch Throwable _)))
          (fs/delete-tree directory {:force true}))))))

(deftest real-sigint-preserves-a-preexisting-converged-writer
  (let [directory (str (fs/create-temp-dir {:prefix "seon-sigint-reuse-"}))
        configuration (signal-fixture-config directory)
        root (:seon.dev.config/root configuration)
        expression
        (str "(require '[seon.dev.process-test :as t]) "
             "(t/run-signal-fixture! " (pr-str directory) " "
             (pr-str process/pod-id) " true)")
        owner (signal-owner! root expression)]
    (try
      (let [pod-record (await-record configuration process/pod-id)
            writer-record (process/read-process configuration process/writer-id)]
        (is (some? pod-record))
        (is (state/process-identity-alive? writer-record))
        (shell/sh {:cmd ["/bin/kill" "-INT"
                         (str (.pid ^java.lang.Process (:proc owner)))]})
        (is (.waitFor ^java.lang.Process (:proc owner) 10 TimeUnit/SECONDS))
        (is (= 130 (:exit @owner)))
        (is (nil? (process/read-process configuration process/watcher-id)))
        (is (nil? (process/read-process configuration process/pod-id)))
        (is (state/process-identity-alive?
             (process/read-process configuration process/writer-id))
            "the invocation never claims or drains the converged writer"))
      (finally
        (doseq [id (reverse process/target-processes)]
          (try (process/stop! configuration id) (catch Throwable _)))
        (fs/delete-tree directory {:force true})))))

(deftest failed-unwind-reports-and-retains-exact-process-identity
  (let [configuration (test-config)
        id :seon.dev.process/interrupted-start
        spec (harmless-spec id ["sleep" "300"])
        fail-stop? (atom false)
        stop @#'process/stop!]
    (try
      (let [failure
            (try
              (with-redefs-fn
                {#'process/stop!
                 (fn [selected id]
                   (if @fail-stop?
                     (throw (ex-info "injected inverse failure"
                                     {:seon.dev.process/id id}))
                     (stop selected id)))}
                (fn []
                  (process/with-startup-ownership
                   configuration
                   (fn [start-owned!]
                     (process/ensure! configuration spec start-owned!)
                     (reset! fail-stop? true)
                     (throw (ex-info "injected startup failure" {}))))))
              nil
              (catch Throwable error error))
            record (process/read-process configuration id)]
        (is (= "Seon startup failed and cleanup was incomplete."
               (ex-message failure)))
        (is (= "injected startup failure"
               (:seon.dev.process/startup-message (ex-data failure))))
        (is (= id
               (get-in (ex-data failure)
                       [:seon.dev.process/unwind-data
                        :seon.dev.process/unwind-failures 0
                        :seon.dev.process/id])))
        (is (= "injected startup failure"
               (some-> failure ex-cause ex-message)))
        (is (= "Failed to unwind every startup resource acquired by this invocation."
               (some-> failure .getSuppressed first ex-message)))
        (is (state/process-identity-alive? record)
            "failed cleanup retains the exact retryable managed record"))
      (finally
        (reset! fail-stop? false)
        (try (stop configuration id) (catch Throwable _))
        (fs/delete-tree (:seon.dev.test/directory configuration)
                        {:force true})))))

(deftest startup-failure-unwinds-new-processes-in-reverse-order
  (let [configuration (test-config)
        stopped (atom [])]
    (try
      (is (thrown-with-msg?
           Exception #"injected transition failure"
           (with-redefs-fn
             {#'process/stop!
              (fn [_ id]
                (swap! stopped conj id))}
             (fn []
               (process/with-startup-ownership
                configuration
                (fn [start-owned!]
                  (doseq [id process/target-processes]
                    (start-owned! id (constantly id)))
                  (throw (ex-info "injected transition failure" {}))))))))
      (is (= (reverse process/target-processes) @stopped))
      (finally
        (fs/delete-tree (:seon.dev.test/directory configuration)
                        {:force true})))))

(deftest process-identity-and-idempotence
  (let [configuration (test-config)
        id :seon.dev.process/dummy
        spec (harmless-spec id ["sleep" "300"])]
    (try
      (let [first-record (process/ensure! configuration spec)
            second-record (process/ensure! configuration spec)
            ambient-noise-spec
            (update spec :seon.dev.process/environment
                    assoc "CODEX_THREAD_ID" (str (random-uuid)))
            ambient-record (process/ensure! configuration ambient-noise-spec)
            boot-config-spec
            (update spec :seon.dev.process/environment
                    assoc "SEON_CONFIG" "/tmp/one-boot.edn")
            boot-config-record (process/ensure! configuration boot-config-spec)]
        (is (= (:seon.dev.process/pid first-record)
               (:seon.dev.process/pid second-record)))
        (is (state/process-identity-alive? first-record))
        (is (not= (:seon.dev.process/pid first-record)
                  (:seon.dev.process/process-group first-record))
            "the persistent owner remains outside the anchored group")
        (is (= (:seon.dev.process/process-group first-record)
               (get-in first-record
                       [:seon.dev.process/containment
                        :seon.dev.process.containment/anchor-pid])))
        (is (= (:seon.dev.process/pid first-record)
               (:seon.dev.process/pid ambient-record))
            "ambient operator variables do not restart the managed process")
        (is (= (:seon.dev.process/pid first-record)
               (:seon.dev.process/pid boot-config-record))
            "operation-scoped boot config is not permanent process identity")
        (let [result (shell/sh {:out :string :err :string :continue true
                                :cmd ["ps" "-p"
                                      (str (:seon.dev.process/pid first-record))
                                      "-o" "pgid="]})]
          (is (= (:seon.dev.process/pid first-record)
                 (some-> (:out result) str/trim parse-long)))))
      (finally (cleanup! configuration [id])))))

(deftest path-identity-follows-selected-executables-not-task-ordering
  (let [directory (fs/create-temp-dir {:prefix "seon-path-identity-"})
        shared (fs/path directory "shared")
        first-path (fs/path directory "first")
        second-path (fs/path directory "second")
        codex-one (fs/path directory ".codex/tmp/arg0/task-one")
        codex-two (fs/path directory ".codex/tmp/arg0/task-two")]
    (try
      (doseq [path [shared first-path second-path codex-one codex-two]]
        (fs/create-dirs path))
      (let [shared-tool (executable! (fs/path shared "bb"))
            _ (fs/create-sym-link (fs/path codex-one "bb") shared-tool)
            _ (fs/create-sym-link (fs/path codex-two "bb") shared-tool)
            _ (executable! (fs/path first-path "alpha"))
            _ (executable! (fs/path second-path "beta"))
            base {"HOME" "/home/seon"
                  "SEON_SHELL" "1"}
            left (assoc base
                        "PATH" (str/join ":" [codex-one first-path second-path])
                        "PWD" "/task/one"
                        "OLDPWD" "/task/one/old"
                        "SHLVL" "2"
                        "_" "/usr/bin/env"
                        "CODEX_THREAD_ID" "task-one")
            right (assoc base
                         "PATH" (str/join ":" [second-path codex-two first-path])
                         "PWD" "/task/two"
                         "OLDPWD" "/task/two/old"
                         "SHLVL" "9"
                         "_" "/bin/sh"
                         "CODEX_THREAD_ID" "task-two")
            without-unrelated (assoc left "PATH" (str codex-one))]
        (is (= (#'process/managed-environment left ["bb"])
               (#'process/managed-environment right ["bb"]))
            "wrapper aliases and unrelated PATH ordering have one identity")
        (is (= (#'process/environment-digest left ["bb"])
               (#'process/environment-digest right ["bb"])))
        (is (= (#'process/environment-digest left ["bb"])
               (#'process/environment-digest without-unrelated ["bb"]))
            "unrelated executable installation is not process identity")
        (is (= (#'process/environment-digest left [(str shared-tool)])
               (#'process/environment-digest right [(str shared-tool)]))
            "an absolute process executable does not depend on PATH")
        (is (not= (get left "PATH") (get right "PATH"))
            "the launched child retains the caller's executable PATH"))
      (finally
        (fs/delete-tree directory {:force true})))))

(deftest path-identity-detects-selected-executable-and-config-drift
  (let [directory (fs/create-temp-dir {:prefix "seon-path-selection-"})
        first-path (fs/path directory "first")
        second-path (fs/path directory "second")]
    (try
      (doseq [path [first-path second-path]]
        (fs/create-dirs path)
        (executable! (fs/path path "node")))
      (let [first {"PATH" (str/join ":" [first-path second-path])
                   "SEON_SHELL" "1"}
            second (assoc first "PATH"
                          (str/join ":" [second-path first-path]))]
        (is (not= (#'process/environment-digest first ["node"])
                  (#'process/environment-digest second ["node"]))
            "changing the selected executable remains process identity")
        (is (not= (#'process/environment-digest first ["node"])
                  (#'process/environment-digest
                   (assoc first "SEON_SHELL" "0") ["node"]))
            "declared Seon configuration remains process identity")
        (is (thrown-with-msg?
             Exception #"executable is missing"
             (#'process/environment-digest first [])))
        (is (thrown-with-msg?
             Exception #"must be absolute"
             (#'process/environment-digest first ["tools/node"])))
        (is (thrown-with-msg?
             Exception #"PATH is missing"
             (#'process/environment-digest
              (dissoc first "PATH") ["node"])))
        (is (thrown-with-msg?
             Exception #"not resolvable"
             (#'process/environment-digest first ["missing-command"]))))
      (finally
        (fs/delete-tree directory {:force true})))))

(deftest ensure-refuses-to-replace-a-nonconverged-managed-process
  (let [record {:seon.dev.process/id process/pod-id
                :seon.dev.process/pid 41}
        stopped (atom [])
        started (atom [])
        spec {:seon.dev.process/id process/pod-id
              :seon.dev.process/external-dependencies []}]
    (with-redefs [process/converged? (constantly false)
                  process/read-process (fn [_ _] record)
                  process/stop! (fn [& arguments]
                                  (swap! stopped conj arguments))
                  process/accepting-unmanaged? (constantly false)]
      (try
        (process/ensure! {} spec
                         (fn [& arguments]
                           (swap! started conj arguments)))
        (is false "replacement without lifecycle evidence must fail")
        (catch clojure.lang.ExceptionInfo error
          (is (= "Refusing to replace a managed process without clean-or-force evidence."
                 (ex-message error)))
          (is (= :seon.dev.process.status/managed-process-present
                 (:seon.dev.process/status (ex-data error))))))
      (is (empty? @stopped)
          "generic reconciliation never invokes the low-level inverse")
      (is (empty? @started)
          "replacement cannot start before the selected generation is absent"))))

(deftest prepare-watcher-refuses-to-replace-a-managed-generation
  (let [record {:seon.dev.process/id process/watcher-id
                :seon.dev.process/pid 42}
        stopped (atom [])
        started (atom [])
        spec (harmless-spec process/watcher-id ["sleep" "300"])]
    (with-redefs [process/watcher-spec (fn [_ _ _] spec)
                  process/read-process (fn [_ _] record)
                  process/stop! (fn [& arguments]
                                  (swap! stopped conj arguments))
                  process/accepting-unmanaged? (constantly false)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"without clean-or-force evidence"
           (process/prepare-watcher!
            {}
            (fn [& arguments]
              (swap! started conj arguments)))))
      (is (empty? @stopped)
          "watcher publication never bypasses lifecycle classification")
      (is (empty? @started)
          "a prepared watcher starts only after exact absence"))))

(deftest publication-cuts-drain-the-unadopted-generation
  (doseq [cut [:before-record :after-record-before-adopt]]
    (let [configuration (test-config)
          id (keyword "seon.dev.process" (name cut))
          spec (harmless-spec id ["sleep" "300"])
          launch (atom nil)
          abort @#'process/abort-unpublished-containment!
          write @#'process/write-process!
          adopt @#'process/adopt-containment!]
      (try
        (is (thrown? clojure.lang.ExceptionInfo
                     (with-redefs-fn
                       {#'process/abort-unpublished-containment!
                        (fn [selected value]
                          (reset! launch value)
                          (abort selected value))
                        #'process/write-process!
                        (if (= cut :before-record)
                          (fn [_ _ _]
                            (throw (ex-info "injected record failure" {})))
                          write)
                        #'process/adopt-containment!
                        (if (= cut :after-record-before-adopt)
                          (fn [_]
                            (throw (ex-info "injected adoption failure" {})))
                          adopt)}
                       #(process/ensure! configuration spec))))
        (is (some? @launch) "the helper published one exact generation")
        (is (not-any? some?
                      (map (comp state/process-start-instant @launch)
                           [:owner_pid :anchor_pid :workload_pid]))
            "every unadopted process is absent before failure returns")
        (is (nil? (process/read-process configuration id)))
        (finally
          (when @launch
            (hard-clean-containment-fixture!
             {:seon.dev.process/containment
               {:seon.dev.process.containment/owner-pid
               (:owner_pid @launch)
               :seon.dev.process.containment/anchor-pid
               (:anchor_pid @launch)
               :seon.dev.process.containment/anchor-start-instant
               (state/process-start-instant (:anchor_pid @launch))
               :seon.dev.process.containment/workload-pid
               (:workload_pid @launch)
               :seon.dev.process.containment/workload-start-instant
               (state/process-start-instant (:workload_pid @launch))
               :seon.dev.process.containment/control-socket
               (:control_socket @launch)
               :seon.dev.process.containment/owner-start-instant
               (state/process-start-instant (:owner_pid @launch))}}))
          (fs/delete-tree (:seon.dev.test/directory configuration)
                          {:force true}))))))

(deftest owner-failure-during-adoption-reaps-the-anchor
  (let [configuration (test-config)
        directory (:seon.dev.test/directory configuration)
        generation (str (random-uuid))
        descriptor (str (fs/path directory "descriptor.json"))
        control (str (fs/path directory "control.sock"))
        result (str (fs/path directory "result.json"))
        launch (atom nil)
        helper (str (fs/path (:seon.dev.config/root configuration)
                             "script/seon/dev/detach.py"))]
    (try
      (fs/create-dirs (fs/parent control))
      (let [started
            (shell/sh
             {:continue true :out :string :err :string
              :cmd ["python3" helper "launch"
                    (:seon.dev.config/root configuration)
                    (str (fs/path directory "failure.log")) generation
                    descriptor control result
                    "100" "sleep" "300"]})
            published (json/parse-string (str/trim (:out started)) true)
            identities
            (mapv (fn [key]
                    (let [pid (get published key)]
                      {:seon.dev.process/pid pid
                       :seon.dev.process/start-instant
                       (state/process-start-instant pid)}))
                  [:owner_pid :anchor_pid :workload_pid])]
        (reset! launch published)
        (is (zero? (:exit started)))
        (fs/create-dirs (:adoption_path published))
        (is (not= (str "adopted " generation)
                  (#'process/socket-line! control
                                           (str "adopt " generation))))
        (loop [remaining 500]
          (when (and (pos? remaining) (not (fs/regular-file? result)))
            (Thread/sleep 10)
            (recur (dec remaining))))
        (loop [remaining 500]
          (when (and (pos? remaining)
                     (some state/process-identity-alive? identities))
            (Thread/sleep 10)
            (recur (dec remaining))))
        (let [terminal (json/parse-string (slurp result) true)]
          (is (= {:generation generation :status "drained"
                  :trigger "requested" :anchor_exit -9}
                 terminal))
          (is (not-any? state/process-identity-alive? identities))
          (is (not (fs/exists? control)))))
      (finally
        (when @launch
          (hard-clean-containment-fixture!
           {:seon.dev.process/containment
            {:seon.dev.process.containment/owner-pid (:owner_pid @launch)
             :seon.dev.process.containment/owner-start-instant
             (state/process-start-instant (:owner_pid @launch))
             :seon.dev.process.containment/anchor-pid (:anchor_pid @launch)
             :seon.dev.process.containment/anchor-start-instant
             (state/process-start-instant (:anchor_pid @launch))
             :seon.dev.process.containment/workload-pid
             (:workload_pid @launch)
             :seon.dev.process.containment/workload-start-instant
             (state/process-start-instant (:workload_pid @launch))
             :seon.dev.process.containment/control-socket control}}))
        (fs/delete-if-exists control)
        (fs/delete-tree directory {:force true})))))

(deftest owner-term-before-adoption-drains-its-owned-generation
  (let [configuration (test-config)
        directory (:seon.dev.test/directory configuration)
        generation (str (random-uuid))
        control (str (fs/path (:seon.dev.config/root configuration)
                              "tmp" "seon-containment"
                              (str generation ".sock")))
        result (str (fs/path directory "term-result.json"))
        helper (str (fs/path (:seon.dev.config/root configuration)
                             "script/seon/dev/detach.py"))]
    (try
      (fs/create-dirs (fs/parent control))
      (let [started
            (shell/sh
             {:continue true :out :string :err :string
              :cmd ["python3" helper "launch"
                    (:seon.dev.config/root configuration)
                    (str (fs/path directory "term.log")) generation
                    (str (fs/path directory "term-descriptor.json"))
                    control result "100" "sleep" "300"]})
            published (json/parse-string (str/trim (:out started)) true)
            identities
            (mapv (fn [key]
                    (let [pid (get published key)]
                      {:seon.dev.process/pid pid
                       :seon.dev.process/start-instant
                       (state/process-start-instant pid)}))
                  [:owner_pid :anchor_pid :workload_pid])]
        (is (zero? (:exit started)))
        (shell/sh {:cmd ["/bin/kill" "-TERM"
                         (str (:owner_pid published))]})
        (loop [remaining 500]
          (when (and (pos? remaining) (not (fs/regular-file? result)))
            (Thread/sleep 10)
            (recur (dec remaining))))
        (loop [remaining 500]
          (when (and (pos? remaining)
                     (some state/process-identity-alive? identities))
            (Thread/sleep 10)
            (recur (dec remaining))))
        (is (= {:generation generation :status "drained"
                :trigger "requested" :anchor_exit -9}
               (json/parse-string (slurp result) true)))
        (is (not-any? state/process-identity-alive? identities))
        (is (not (fs/exists? control))))
      (finally
        (fs/delete-if-exists control)
        (fs/delete-tree directory {:force true})))))

(deftest stop-drains-descendants
  (let [configuration (test-config)
        id :seon.dev.process/tree
        child-file (str (fs/path (:seon.dev.test/directory configuration) "child"))
        spec (harmless-spec
               id ["bash" "-c"
                   (str "sleep 300 & child=$!; echo $child > " child-file
                        "; wait $child")])]
    (try
      (let [record (process/ensure! configuration spec)]
        (loop [remaining 50]
          (when (and (pos? remaining) (not (fs/regular-file? child-file)))
            (Thread/sleep 20)
            (recur (dec remaining))))
        (is (fs/regular-file? child-file))
        (let [child (parse-long (str/trim (slurp child-file)))]
          (process/stop! configuration id)
          (is (not (state/process-identity-alive? record)))
          (is (nil? (state/process-start-instant child)))))
      (finally (cleanup! configuration [id])))))

(deftest workload-receives-term-before-anchor-escalation
  (let [configuration (test-config)
        id :seon.dev.process/term-delivery
        directory (:seon.dev.test/directory configuration)
        ready-file (str (fs/path directory "term-ready"))
        term-file (str (fs/path directory "term-received"))
        source
        (str "import signal,sys,time\n"
             "term,ready=sys.argv[1],sys.argv[2]\n"
             "def on_term(signum,frame):\n"
             " open(term,'w').write('TERM')\n"
             " raise SystemExit(0)\n"
             "signal.signal(signal.SIGTERM,on_term)\n"
             "open(ready,'w').write('ready')\n"
             "while True: time.sleep(1)\n")
        spec (harmless-spec id ["python3" "-c" source
                                term-file ready-file])]
    (try
      (process/ensure! configuration spec)
      (loop [remaining 100]
        (when (and (pos? remaining) (not (fs/regular-file? ready-file)))
          (Thread/sleep 10)
          (recur (dec remaining))))
      (is (fs/regular-file? ready-file))
      (process/stop! configuration id)
      (is (= "TERM" (slurp term-file))
          "the workload inherits SIG_DFL and runs its graceful TERM handler")
      (finally (cleanup! configuration [id])))))

(deftest writer-application-result-is-captured-inside-the-terminal-result
  (let [configuration (test-config)
        id process/writer-id
        ready-file (str (fs/path (:seon.dev.test/directory configuration)
                                 "writer-terminal-ready"))
        source
        (str "import os,signal,sys,time\n"
             "ready=sys.argv[1]\n"
             "def on_term(signum,frame):\n"
             " generation=os.environ['SEON_PROCESS_GENERATION']\n"
             " value='{:seon.db.terminal/generation \\\"'+generation+'\\\" :seon.db.terminal/process :seon.dev.process/writer :seon.db.terminal/completed? true :seon.db.terminal/stop-response {:seon.db.server/stopped? true :seon.db.server/release-results []}}\\n'\n"
             " open(os.environ['SEON_APPLICATION_RESULT_PATH'],'w').write(value)\n"
             " raise SystemExit(0)\n"
             "signal.signal(signal.SIGTERM,on_term)\n"
             "open(ready,'w').write('ready')\n"
             "while True: time.sleep(1)\n")
        spec (harmless-spec id ["python3" "-c" source ready-file])]
    (try
      (let [record (process/ensure! configuration spec)
            containment (:seon.dev.process/containment record)]
        (loop [remaining 100]
          (when (and (pos? remaining) (not (fs/regular-file? ready-file)))
            (Thread/sleep 10)
            (recur (dec remaining))))
        (is (string?
             (:seon.dev.process.containment/application-result-path
              containment)))
        (let [result (process/stop! configuration id)]
          (is (= :seon.dev.process.containment.trigger/requested
                 (get-in result
                         [:seon.dev.process/terminal
                          :seon.dev.process.containment/trigger])))
          (is (= (str (:seon.dev.process.containment/generation containment))
                 (get-in result
                         [:seon.dev.process/application-result
                          :seon.db.terminal/generation])))
          (is (true?
               (get-in result
                       [:seon.dev.process/application-result
                        :seon.db.terminal/stop-response
                        :seon.db.server/stopped?]))))
        (is (nil? (process/read-process configuration id))))
      (finally (cleanup! configuration [id])))))

(deftest writer-application-evidence-fails-closed-at-every-inner-cut
  (let [generation (random-uuid)
        containment
        {:seon.dev.process.containment/generation generation
         :seon.dev.process.containment/application-result-path
         "application.edn"}
        terminal-value
        (fn [selected-generation]
          {:seon.db.terminal/generation (str selected-generation)
           :seon.db.terminal/process :seon.dev.process/writer
           :seon.db.terminal/completed? true
           :seon.db.terminal/stop-response
           {:seon.db.server/stopped? true
            :seon.db.server/release-results []}})
        capture
        (fn [text]
          {:application_result
           {:status "captured"
            :edn text
            :sha256 (#'process/sha256-text text)}})
        application-error
        (fn [terminal]
          (:seon.dev.process/application-error
           (#'process/writer-application-evidence containment terminal)))]
    (is (= :seon.dev.process.application-error/missing-capture
           (application-error {})))
    (is (= :seon.dev.process.application-error/missing
           (application-error {:application_result {:status "missing"}})))
    (is (= :seon.dev.process.application-error/digest-mismatch
           (application-error
            {:application_result
             {:status "captured" :edn "{}" :sha256 "crossed"}})))
    (is (= :seon.dev.process.application-error/invalid-schema
           (application-error (capture "{}"))))
    (is (= :seon.dev.process.application-error/generation-mismatch
           (application-error (capture (pr-str (terminal-value
                                                (random-uuid)))))))
    (is (= :seon.dev.process.application-error/malformed-edn
           (application-error
            (capture (str (pr-str (terminal-value generation)) " {}")))))))

(defn- requested-stop-result [id]
  {:seon.dev.process/id id
   :seon.dev.process/terminal
   {:seon.dev.process.containment/generation (random-uuid)
    :seon.dev.process.containment/status
    :seon.dev.process.containment.status/drained
    :seon.dev.process.containment/trigger
    :seon.dev.process.containment.trigger/requested
    :seon.dev.process.containment/anchor-exit -9}})

(deftest clean-or-force-orders-and-classifies-complete-evidence
  (let [database-id (random-uuid)
        coordinate {:seon.db.coordinate/database-id database-id
                    :seon.db.coordinate/branch :db
                    :seon.db.coordinate/commit-id (random-uuid)
                    :seon.db.coordinate/t 42}
        configuration
        (assoc (operator-config) :seon.dev.config/environment
               {"SEON_TURN_TIMEOUT_MS" "10"})
        calls (atom [])
        writer-result
        (assoc (requested-stop-result process/writer-id)
               :seon.dev.process/application-result
               {:seon.db.terminal/generation (str (random-uuid))
                :seon.db.terminal/process :seon.dev.process/writer
                :seon.db.terminal/completed? true
                :seon.db.terminal/stop-response
                {:seon.db.server/stopped? true
                 :seon.db.server/release-results
                 [{:seon.db.registry/database-name :db
                   :seon.db.registry/attachment
                   {:seon.db.coordinate/database-id database-id
                    :seon.db.coordinate/branch :db}
                   :seon.db.registry/coordinate coordinate
                   :seon.db.registry/released? true}]}})
        pod-result
        {:seon.client/quiesced? true
         :seon.db.coordinate/coordinate coordinate
         :seon.client/quiesced-run-ids []
         :seon.client/completed-turn-ids []
         :seon.client/errored-turn-ids []
         :seon.agent.runtime/unhosted-ids []}]
    (with-redefs [process/read-process (fn [_ _] {:record true})
                  process/stop!
                  (fn [_ id & _]
                    (swap! calls conj id)
                    (if (= process/writer-id id)
                      writer-result
                      (requested-stop-result id)))
                  process/pod-application-evidence
                  (fn [_ _ _]
                    {:seon.dev.process/application-result pod-result})]
      (let [result
            (process/clean-or-force!
             {:seon.dev.process/configuration configuration
              :seon.dev.process/operation
              :seon.dev.process.operation/restart
              :seon.dev.process/targets
              #{process/watcher-id process/pod-id process/writer-id}})]
        (is (= [process/pod-id process/writer-id process/watcher-id]
               @calls))
        (is (= :seon.dev.process.classification/clean
               (:seon.dev.process/classification result)))
        (is (m/validate process/clean-or-force-result-schema result))
        (is (every? #(= :seon.dev.process.classification/clean
                        (:seon.dev.process/classification %))
                    (:seon.dev.process/results result)))))))

(deftest clean-or-force-retains-the-completed-prefix-on-uncertainty
  (let [coordinate {:seon.db.coordinate/database-id (random-uuid)
                    :seon.db.coordinate/branch :db
                    :seon.db.coordinate/commit-id (random-uuid)
                    :seon.db.coordinate/t 42}
        configuration (operator-config)
        calls (atom [])]
    (with-redefs [process/read-process (fn [_ _] {:record true})
                  process/stop!
                  (fn [_ id & _]
                    (swap! calls conj id)
                    (if (= process/writer-id id)
                      (throw (ex-info "uncertain" {:original true}))
                      (requested-stop-result id)))
                  process/pod-application-evidence
                  (fn [_ _ _]
                    {:seon.dev.process/application-result
                     {:seon.client/quiesced? true
                      :seon.db.coordinate/coordinate coordinate
                      :seon.client/quiesced-run-ids []
                      :seon.client/completed-turn-ids []
                      :seon.client/errored-turn-ids []
                      :seon.agent.runtime/unhosted-ids []}})]
      (try
        (process/clean-or-force!
         {:seon.dev.process/configuration configuration
          :seon.dev.process/operation :seon.dev.process.operation/down
          :seon.dev.process/targets
          #{process/watcher-id process/pod-id process/writer-id}})
        (is false "containment uncertainty must throw")
        (catch clojure.lang.ExceptionInfo error
          (is (= :seon.dev.process.classification/containment-uncertain
                 (:seon.dev.process/classification (ex-data error))))
          (is (= [process/pod-id process/writer-id] @calls))
          (is (= [process/pod-id]
                 (mapv :seon.dev.process/id
                       (:seon.dev.process/results (ex-data error))))))))))

(deftest clean-or-force-shares-one-deadline-across-every-selected-phase
  (let [record {:seon.dev.process/record true}
        calls (atom [])]
    (with-redefs [process/read-process (fn [_ _] record)
                  process/pod-application-evidence
                  (fn [_ _ deadline]
                    (swap! calls conj [:pod-application deadline])
                    {:seon.dev.process/application-error
                     :seon.dev.process.application-error/quiesce-failed})
                  process/stop!
                  (fn [_ id selected deadline]
                    (is (= record selected))
                    (swap! calls conj [id deadline])
                    nil)]
      (process/clean-or-force!
       {:seon.dev.process/configuration
        (assoc (operator-config) :seon.dev.config/environment
               {"SEON_TURN_TIMEOUT_MS" "10"})
        :seon.dev.process/operation :seon.dev.process.operation/restart
        :seon.dev.process/targets
        #{process/watcher-id process/writer-id process/pod-id}})
      (is (= [:pod-application
              process/pod-id process/writer-id process/watcher-id]
             (mapv first @calls)))
      (is (= 1 (count (set (map second @calls))))))))

(deftest coordinated-absence-rejects-a-newly-published-generation
  (let [replacement {:seon.dev.process/id process/watcher-id
                     :seon.dev.process/pid 43}
        reads (atom [nil replacement])
        drained (atom [])]
    (with-redefs [process/read-process
                  (fn [_ _]
                    (let [value (first @reads)]
                      (swap! reads next)
                      value))
                  process/drain-containment!
                  (fn [& arguments]
                    (swap! drained conj arguments))]
      (try
        (#'process/stop-selected!
         {} Long/MAX_VALUE [process/watcher-id])
        (is false "a new generation cannot satisfy selected absence")
        (catch clojure.lang.ExceptionInfo error
          (is (= "A managed process could not prove containment absence."
                 (ex-message error)))
          (is (= replacement
                 (:seon.dev.process/current-record (ex-data error))))
          (is (= [] (:seon.dev.process/results (ex-data error))))))
      (is (empty? @drained)
          "the unselected generation is retained without a drain request"))))

(defn- serve-one-lifecycle-response! [server status body observed]
  (future
    (with-open [socket (.accept server)
                reader (BufferedReader.
                        (InputStreamReader. (.getInputStream socket)))]
      (let [request-line (.readLine reader)
            headers
            (loop [values []]
              (let [line (.readLine reader)]
                (if (str/blank? line)
                  values
                  (recur (conj values line)))))]
        (reset! observed {:request-line request-line :headers headers})
        (let [content (.getBytes body java.nio.charset.StandardCharsets/UTF_8)
              response
              (str "HTTP/1.1 " status " Test\r\n"
                   "Content-Type: application/edn\r\n"
                   "Content-Length: " (alength content) "\r\n"
                   "Connection: close\r\n\r\n")
              output (.getOutputStream socket)]
          (.write output (.getBytes response
                                    java.nio.charset.StandardCharsets/UTF_8))
          (.write output content)
          (.flush output))))))

(deftest bounded-quiesce-client-uses-one-closed-loopback-edn-response
  (let [directory (fs/create-temp-dir {:prefix "seon-quiesce-http-"})
        port-file (str (fs/path directory "pod-port"))
        coordinate
        {:seon.db.coordinate/database-id (random-uuid)
         :seon.db.coordinate/branch :db
         :seon.db.coordinate/commit-id (random-uuid)
         :seon.db.coordinate/t 42}
        response
        {:seon.client/quiesced? true
         :seon.db.coordinate/coordinate coordinate
         :seon.client/quiesced-run-ids []
         :seon.client/completed-turn-ids []
         :seon.client/errored-turn-ids []
         :seon.agent.runtime/unhosted-ids []}
        configuration {:seon.dev.config/http-port-file port-file}]
    (try
      (with-open [server (ServerSocket. 0)]
        (spit port-file (str (.getLocalPort server)))
        (let [observed (atom nil)
              served (serve-one-lifecycle-response!
                      server 200 (str (pr-str response) "\n") observed)
              value (#'process/bounded-quiesce-post!
                     configuration (+ (#'process/monotonic-ms) 5000))]
          @served
          (is (= response value))
          (is (= "POST /_seon/operator/quiesce HTTP/1.1"
                 (:request-line @observed)))
          (is (some #(= "Accept: application/edn" %)
                    (:headers @observed)))))
      (with-open [server (ServerSocket. 0)]
        (spit port-file (str (.getLocalPort server)))
        (let [served (serve-one-lifecycle-response!
                      server 200 (str (pr-str response) " {}") (atom nil))]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"trailing data"
               (#'process/bounded-quiesce-post!
                configuration (+ (#'process/monotonic-ms) 5000))))
          @served))
      (with-open [server (ServerSocket. 0)]
        (spit port-file (str (.getLocalPort server)))
        (let [served (serve-one-lifecycle-response!
                      server 409 (pr-str response) (atom nil))]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"status contradicts"
               (#'process/bounded-quiesce-post!
                configuration (+ (#'process/monotonic-ms) 5000))))
          @served))
      (finally (fs/delete-tree directory {:force true})))))

(deftest pod-quiesce-retries-only-one-typed-retryable-result
  (let [calls (atom 0)
        generation (random-uuid)
        record {:seon.dev.process/id process/pod-id
                :seon.dev.process/containment
                {:seon.dev.process.containment/generation generation}}]
    (with-redefs [process/containment-live? (constantly true)
                  process/bounded-quiesce-post!
                  (fn [_ _]
                    (if (= 1 (swap! calls inc))
                      {:seon.client/quiesced? false
                       :seon.client/quiesce-error "retry"
                       :seon.runtime.lifecycle/process-generation
                       (str generation)}
                      {:seon.client/quiesced? true
                       :seon.runtime.lifecycle/process-generation
                       (str generation)}))]
      (is (= {:seon.dev.process/application-result
              {:seon.client/quiesced? true
               :seon.runtime.lifecycle/process-generation (str generation)}}
             (#'process/pod-application-evidence
              {} record (+ (#'process/monotonic-ms) 5000))))
      (is (= 2 @calls)))))

(deftest clean-or-force-rejects-empty-and-unknown-targets
  (doseq [request
          [{:seon.dev.process/configuration {}
            :seon.dev.process/operation :seon.dev.process.operation/down
            :seon.dev.process/targets #{}}
           {:seon.dev.process/configuration {}
            :seon.dev.process/operation :seon.dev.process.operation/down
            :seon.dev.process/targets #{:seon.dev.process/typo}}
           {:seon.dev.process/configuration {}
            :seon.dev.process/operation :seon.dev.process.operation/typo
            :seon.dev.process/targets #{process/pod-id}}
           {}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"request is invalid"
                          (process/clean-or-force! request)))))

(deftest stop-refuses-absence-when-an-unmanaged-door-is-accepting
  (with-redefs [process/read-process (constantly nil)
                process/accepting-unmanaged? (constantly true)]
    (doseq [id process/target-processes]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"accepting without a managed record"
           (process/stop! {} id))))))

(deftest stop-refuses-a-generation-swap-before-drain
  (let [expected {:seon.dev.process/id process/pod-id}
        replacement {:seon.dev.process/id process/pod-id
                     :seon.dev.process/pid 999}]
    (with-redefs [process/read-process (constantly replacement)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"generation changed"
           (process/stop! {} process/pod-id expected))))))

(deftest pod-quiesce-rejects-a-crossed-generation
  (let [generation (random-uuid)
        record
        {:seon.dev.process/id process/pod-id
         :seon.dev.process/containment
         {:seon.dev.process.containment/generation generation}}]
    (with-redefs [process/containment-live? (constantly true)
                  process/bounded-quiesce-post!
                  (fn [_ _]
                    {:seon.client/quiesced? true
                     :seon.runtime.lifecycle/process-generation
                     (str (random-uuid))})]
      (is (= :seon.dev.process.application-error/quiesce-failed
             (:seon.dev.process/application-error
              (#'process/pod-application-evidence
               {} record (+ (#'process/monotonic-ms) 5000))))))))

(deftest dead-workload-drains-a-term-ignoring-descendant-before-replacement
  (let [configuration (test-config)
        id :seon.dev.process/dead-workload
        child-file (str (fs/path (:seon.dev.test/directory configuration)
                                 "term-ignoring-child"))
        spec (harmless-spec
              id ["bash" "-c"
                  (str "trap '' TERM; sleep 300 & child=$!; echo $child > "
                       child-file "; wait $child")])]
    (try
      (let [record (process/ensure! configuration spec)
            containment (:seon.dev.process/containment record)
            workload (:seon.dev.process.containment/workload-pid containment)]
        (loop [remaining 100]
          (when (and (pos? remaining) (not (fs/regular-file? child-file)))
            (Thread/sleep 10)
            (recur (dec remaining))))
        (let [child (parse-long (str/trim (slurp child-file)))]
          (shell/sh {:cmd ["/bin/kill" "-KILL" (str workload)]})
          (loop [remaining 500]
            (when (and (pos? remaining)
                       (not (fs/regular-file?
                             (:seon.dev.process.containment/result-path
                              containment))))
              (Thread/sleep 10)
              (recur (dec remaining))))
          (is (= "workload-exit"
                 (:trigger
                  (json/parse-string
                   (slurp (:seon.dev.process.containment/result-path
                           containment))
                   true))))
          (process/stop! configuration id)
          (is (nil? (state/process-start-instant child))
              "the final anchored KILL removes the TERM-ignoring child")
          (is (nil? (process/read-process configuration id)))
          (let [replacement (process/ensure!
                             configuration
                             (assoc spec :seon.dev.process/argv
                                    ["sleep" "300"]))]
            (is (not= (:seon.dev.process/pid record)
                      (:seon.dev.process/pid replacement))
                "replacement starts only after the terminal result"))))
      (finally (cleanup! configuration [id])))))

(deftest missing-owner-result-is-containment-uncertain
  (let [configuration (test-config)
        id :seon.dev.process/missing-result
        spec (harmless-spec id ["sleep" "300"])
        fixture (atom nil)]
    (try
      (let [record (process/ensure! configuration spec)
            containment (:seon.dev.process/containment record)
            owner (:seon.dev.process.containment/owner-pid containment)
            anchor (:seon.dev.process.containment/anchor-pid containment)]
        (reset! fixture record)
        (shell/sh {:cmd ["/bin/kill" "-KILL" (str owner)]})
        (loop [remaining 500]
          (when (and (pos? remaining)
                     (some? (state/process-start-instant anchor)))
            (Thread/sleep 10)
            (recur (dec remaining))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"disappeared without a terminal result"
             (process/stop! configuration id)))
        (is (some? (process/read-process configuration id)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (process/ensure! configuration spec))))
      (finally
        (when @fixture
          (is (not-any? true? (hard-clean-containment-fixture! @fixture))
              "the failure fixture leaves no containment process alive"))
        (fs/delete-tree (:seon.dev.test/directory configuration)
                        {:force true})))))

(deftest individually-killed-anchor-never-publishes-false-drained-evidence
  (let [configuration (test-config)
        id :seon.dev.process/missing-anchor
        spec (harmless-spec id ["sleep" "300"])
        fixture (atom nil)]
    (try
      (let [record (process/ensure! configuration spec)
            containment (:seon.dev.process/containment record)
            anchor (:seon.dev.process.containment/anchor-pid containment)
            owner-identity
            {:seon.dev.process/pid
             (:seon.dev.process.containment/owner-pid containment)
             :seon.dev.process/start-instant
             (:seon.dev.process.containment/owner-start-instant containment)}
            workload-identity
            {:seon.dev.process/pid
             (:seon.dev.process.containment/workload-pid containment)
             :seon.dev.process/start-instant
             (:seon.dev.process.containment/workload-start-instant containment)}]
        (reset! fixture record)
        (shell/sh {:cmd ["/bin/kill" "-KILL" (str anchor)]})
        (loop [remaining 500]
          (when (and (pos? remaining)
                     (state/process-identity-alive? owner-identity))
            (Thread/sleep 10)
            (recur (dec remaining))))
        (is (not (state/process-identity-alive? owner-identity)))
        (is (state/process-identity-alive? workload-identity)
            "anchor death alone does not prove or silently lose the workload")
        (is (not (fs/exists?
                  (:seon.dev.process.containment/result-path containment))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"disappeared without a terminal result"
             (process/stop! configuration id)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (process/ensure! configuration spec))))
      (finally
        (when @fixture
          (is (not-any? true? (hard-clean-containment-fixture! @fixture))
              "the external anchor-death fixture is fully cleaned"))
        (fs/delete-tree (:seon.dev.test/directory configuration)
                        {:force true})))))

(deftest live-legacy-group-retires-with-its-child-before-upgrade
  (let [configuration (test-config)
        id :seon.dev.process/legacy
        directory (:seon.dev.test/directory configuration)
        child-file (str (fs/path directory "legacy-child"))
        legacy (shell/process
                {:out :discard :err :discard
                 :cmd ["python3" "-c"
                       (str "import os,subprocess,time\n"
                            "os.setsid()\n"
                            "c=subprocess.Popen(['sleep','300'])\n"
                            "open(" (pr-str child-file)
                            ",'w').write(str(c.pid))\n"
                            "time.sleep(300)\n")]})
        pid (.pid ^java.lang.Process (:proc legacy))
        file (fs/path (:seon.dev.config/process-dir configuration)
                      "processes/legacy.edn")]
    (try
      (loop [remaining 100]
        (when (and (pos? remaining) (not (fs/regular-file? child-file)))
          (Thread/sleep 10)
          (recur (dec remaining))))
      (let [child (parse-long (str/trim (slurp child-file)))
            start (state/process-start-instant pid)]
        (state/write-edn!
         file
         {:seon.dev.process/id id
          :seon.dev.process/pid pid
          :seon.dev.process/start-instant start
          :seon.dev.process/process-group pid
          :seon.dev.process/argv ["legacy"]
          :seon.dev.process/environment-digest (apply str (repeat 64 "0"))
          :seon.dev.process/artifact-digest "legacy"
          :seon.dev.process/target :seon.dev.target/development
          :seon.dev.process/started-at "legacy"
          :seon.dev.process/log (str (fs/path directory "legacy.log"))})
        (process/stop! configuration id)
        (is (nil? (state/process-start-instant pid)))
        (is (nil? (state/process-start-instant child)))
        (is (nil? (process/read-process configuration id))))
      (finally
        (when (.isAlive ^java.lang.Process (:proc legacy))
          (.destroyForcibly ^java.lang.Process (:proc legacy)))
        (fs/delete-tree directory {:force true})))))

(deftest reused-pid-is-never-signaled
  (let [configuration (test-config)
        id :seon.dev.process/stale
        innocent (shell/process {:cmd ["sleep" "300"]})
        pid (.pid ^java.lang.Process (:proc innocent))
        file (fs/path (:seon.dev.config/process-dir configuration)
                      "processes/stale.edn")]
    (try
      (state/write-edn!
        file {:seon.dev.process/id id
              :seon.dev.process/pid pid
              :seon.dev.process/start-instant "not-the-live-start-instant"
              :seon.dev.process/process-group pid
              :seon.dev.process/argv ["sleep" "300"]
              :seon.dev.process/environment-digest (apply str (repeat 64 "0"))
              :seon.dev.process/artifact-digest "test-artifact"
              :seon.dev.process/target :seon.dev.target/development
              :seon.dev.process/started-at "test"
              :seon.dev.process/log
              (str (fs/path (:seon.dev.test/directory configuration)
                            "stale.log"))})
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"cannot be retired safely"
           (process/stop! configuration id)))
      (is (.isAlive ^java.lang.Process (:proc innocent)))
      (is (some? (process/read-process configuration id))
          "uncertain legacy evidence is retained and blocks replacement")
      (finally
        (.destroyForcibly ^java.lang.Process (:proc innocent))
        (fs/delete-tree (:seon.dev.test/directory configuration))))))

(deftest lifecycle-lock-serializes-and-cleans-up
  (let [configuration (test-config)
        entered (promise)
        released (promise)
        holder (future
                 (state/with-lock
                   configuration :stack 5000
                   #(do (deliver entered true)
                        (Thread/sleep 300)
                        (deliver released true))))]
    (try
      @entered
      (state/with-lock configuration :stack 5000 #(is (realized? released)))
      @holder
      (testing "a failed transition does not leak its owned lock"
        (is (thrown? Exception
                     (state/with-lock configuration :failure 5000
                                      #(throw (ex-info "expected" {})))))
        (is (= :released
               (state/with-lock configuration :failure 5000
                                (constantly :released)))))
      (finally (fs/delete-tree (:seon.dev.test/directory configuration))))))

(deftest unmanaged-listener-is-never-unlinked
  (let [configuration (test-config)
        port (with-open [socket (ServerSocket. 0)] (.getLocalPort socket))
        port-file (str (fs/path (:seon.dev.test/directory configuration) "pod-port"))
        configuration (assoc configuration
                        :seon.dev.config/http-port port
                        :seon.dev.config/http-port-file port-file)
        server (shell/process {:out :discard :err :discard
                               :cmd ["python3" "-m" "http.server" (str port)
                                     "--bind" "127.0.0.1"]})
        spec (harmless-spec process/pod-id ["sleep" "300"])]
    (try
      (loop [remaining 50]
        (let [probe (shell/sh {:continue true :out :string :err :string
                               :cmd ["curl" "-fsS" "-m" "1"
                                     (str "http://127.0.0.1:" port "/")]})]
          (when (and (pos? remaining) (not (zero? (:exit probe))))
            (Thread/sleep 20)
            (recur (dec remaining)))))
      (spit port-file (str port))
      (is (thrown? Exception (process/ensure! configuration spec)))
      (is (fs/regular-file? port-file))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"accepting without a managed record"
           (process/stop! configuration process/pod-id)))
      (is (fs/regular-file? port-file))
      (finally
        (.destroyForcibly ^java.lang.Process (:proc server))
        (cleanup! configuration [process/pod-id])))))

(deftest latest-watcher-result-is-readiness-truth
  (let [configuration (test-config)
        configuration
        (assoc configuration
               :seon.dev.config/client-build-id "client"
               :seon.dev.config/execution-build-id "execution"
               :seon.dev.config/artifact-flavor
               :seon.dev.artifact.flavor/default)
        log (fs/path (:seon.dev.test/directory configuration) "watcher.log")
        pid (.pid (java.lang.ProcessHandle/current))
        record (live-probe-record
                configuration
                {:seon.dev.process/id process/watcher-id
                 :seon.dev.process/pid pid
                 :seon.dev.process/start-instant
                 (state/process-start-instant pid)
                 :seon.dev.process/log (str log)})
        spec {:seon.dev.process/id process/watcher-id
              :seon.dev.process/readiness :seon.dev.process.readiness/watcher}]
    (try
      (spit (str log) (str "[:client] Compiling ...\n"
                           "[:client] Build completed.\n"
                           "[:execution] Compiling ...\n"
                           "[:execution] Build completed.\n"
                           "[:test] Compiling ...\n"
                           "[:test] Build completed.\n"))
      (is (process/ready? configuration spec record))
      (spit (str log) "[:client] Compiling ...\n" :append true)
      (is (not (process/ready? configuration spec record)))
      (spit (str log) "[:client] Build failure:\n" :append true)
      (is (not (process/ready? configuration spec record)))
      (spit (str log) "[:client] Build completed.\n" :append true)
      (is (process/ready? configuration spec record))
      (spit (str log) "[:test] Compiling ...\n" :append true)
      (is (not (process/ready? configuration spec record)))
      (spit (str log) "[:test] Build failure:\n" :append true)
      (is (not (process/ready? configuration spec record)))
      (spit (str log) "[:test] Build completed.\n" :append true)
      (is (process/ready? configuration spec record))
      (spit (str log) "[:execution] Compiling ...\n" :append true)
      (is (not (process/ready? configuration spec record)))
      (spit (str log) "[:execution] Build failure:\n" :append true)
      (is (not (process/ready? configuration spec record)))
      (spit (str log) "[:execution] Build completed.\n" :append true)
      (is (process/ready? configuration spec record))
      (finally (fs/delete-tree (:seon.dev.test/directory configuration))))))

(deftest pod-readiness-follows-current-admission-response
  (let [configuration (test-config)
        directory (:seon.dev.test/directory configuration)
        port-file (str (fs/path directory "pod-port"))
        configuration (assoc configuration
                             :seon.dev.config/http-port-file port-file)
        !status (atom 200)
        server (ServerSocket. 0)
        server-loop
        (future
          (try
            (while (not (.isClosed server))
              (with-open [socket (.accept server)
                          input (BufferedReader.
                                  (InputStreamReader.
                                    (.getInputStream socket) "UTF-8"))
                          output (.getOutputStream socket)]
                ;; Consume the request headers; the ready probe sends no body.
                (loop []
                  (when-let [line (.readLine input)]
                    (when-not (str/blank? line) (recur))))
                (let [status @!status
                      reason (if (= 200 status) "OK" "Service Unavailable")
                      body "{}"
                      response
                      (str "HTTP/1.1 " status " " reason "\r\n"
                           "Content-Type: application/edn\r\n"
                           "Content-Length: " (count body) "\r\n"
                           "Connection: close\r\n\r\n"
                           body)]
                  (.write output (.getBytes response "UTF-8"))
                  (.flush output))))
            (catch SocketException _)))
        port (.getLocalPort server)
        _ (spit port-file (str port))
        pid (.pid (java.lang.ProcessHandle/current))
        record (live-probe-record
                configuration
                {:seon.dev.process/id process/pod-id
                 :seon.dev.process/pid pid
                 :seon.dev.process/start-instant
                 (state/process-start-instant pid)})
        spec {:seon.dev.process/id process/pod-id
              :seon.dev.process/readiness :seon.dev.process.readiness/pod}]
    (try
      (is (process/ready? configuration spec record))
      (reset! !status 503)
      (is (not (process/ready? configuration spec record))
          "readiness can close after the process previously became ready")
      (reset! !status 200)
      (is (process/ready? configuration spec record))
      (finally
        (.close server)
        @server-loop
        (fs/delete-tree directory)))))

(deftest acme-watcher-readiness-owns-only-the-acme-client-build
  (let [configuration (test-config)
        configuration
        (assoc configuration
               :seon.dev.config/client-build-id "acme-client"
               :seon.dev.config/execution-build-id "acme-execution"
               :seon.dev.config/artifact-flavor
               :seon.dev.artifact.flavor/acme)
        log (fs/path (:seon.dev.test/directory configuration) "watcher.log")
        pid (.pid (java.lang.ProcessHandle/current))
        record (live-probe-record
                configuration
                {:seon.dev.process/id process/watcher-id
                 :seon.dev.process/pid pid
                 :seon.dev.process/start-instant
                 (state/process-start-instant pid)
                 :seon.dev.process/log (str log)})
        spec {:seon.dev.process/id process/watcher-id
              :seon.dev.process/readiness :seon.dev.process.readiness/watcher}]
    (try
      (spit (str log) (str "[:acme-client] Build completed.\n"
                           "[:acme-execution] Build completed.\n"))
      (is (process/ready? configuration spec record))
      (spit (str log) "[:test] Compiling ...\n" :append true)
      (is (process/ready? configuration spec record)
          "the ACME watcher neither owns nor awaits the default test build")
      (spit (str log) "[:acme-client] Compiling ...\n" :append true)
      (is (not (process/ready? configuration spec record)))
      (finally (fs/delete-tree (:seon.dev.test/directory configuration))))))

(deftest completed-watcher-is-not-ready-after-client-byte-drift
  (let [configuration (test-config)
        directory (:seon.dev.test/directory configuration)
        output (fs/path directory "out-acme/client/main.js")
        execution-output (fs/path directory "out-acme/execution/main.js")
        runtime (fs/path directory
                         "shadow-acme/builds/acme-client/dev/out/cljs-runtime/a.js")
        configuration
        (assoc configuration
               :seon.dev.config/client-build-id "acme-client"
               :seon.dev.config/execution-build-id "acme-execution"
               :seon.dev.config/artifact-flavor
               :seon.dev.artifact.flavor/acme
               :seon.dev.config/client-output (str output)
               :seon.dev.config/execution-output (str execution-output)
               :seon.dev.config/shadow-cache-root
               (str (fs/path directory "shadow-acme")))
        log (fs/path directory "watcher.log")
        pid (.pid (java.lang.ProcessHandle/current))
        record (live-probe-record
                configuration
                {:seon.dev.process/id process/watcher-id
                 :seon.dev.process/pid pid
                 :seon.dev.process/start-instant
                 (state/process-start-instant pid)
                 :seon.dev.process/log (str log)})]
    (try
      (fs/create-dirs (fs/parent output))
      (fs/create-dirs (fs/parent execution-output))
      (fs/create-dirs (fs/parent runtime))
      (spit (str output) "main-a")
      (spit (str execution-output) "execution-a")
      (spit (str runtime) "runtime-a")
      (spit (str log) (str "[:acme-client] Build completed.\n"
                           "[:acme-execution] Build completed.\n"))
      (let [digest (artifact/current-client-digest configuration)
            execution-digest
            (artifact/current-execution-digest configuration)
            spec {:seon.dev.process/id process/watcher-id
                  :seon.dev.process/readiness
                  :seon.dev.process.readiness/watcher
                  :seon.dev.process/artifact-digest digest
                  :seon.dev.process/client-digest digest
                  :seon.dev.process/execution-digest execution-digest}]
        (is (process/ready? configuration spec record))
        (spit (str runtime) "runtime-b")
        (is (not (process/ready? configuration spec record))
            "successful hot reload bytes cannot retain the old identity"))
      (finally (fs/delete-tree directory)))))

(deftest prepared-watcher-admits-only-the-published-client-bytes
  (let [configuration (test-config)
        directory (:seon.dev.test/directory configuration)
        output (fs/path directory "client.js")
        execution-output (fs/path directory "execution.js")
        runtime (fs/path directory
                         "shadow/builds/client/dev/out/cljs-runtime/a.js")
        log (fs/path directory "watcher.log")
        configuration
        (assoc configuration
               :seon.dev.config/artifact-flavor
               :seon.dev.artifact.flavor/default
               :seon.dev.config/client-build-id "client"
               :seon.dev.config/client-output (str output)
               :seon.dev.config/execution-build-id "execution"
               :seon.dev.config/execution-output (str execution-output)
               :seon.dev.config/shadow-cache-root
               (str (fs/path directory "shadow")))
        spec (#'process/watcher-spec
              configuration @#'process/unpublished-client-digest nil)
        pid (.pid (java.lang.ProcessHandle/current))
        record {:seon.dev.process/id process/watcher-id
                :seon.dev.process/pid pid
                :seon.dev.process/start-instant
                (state/process-start-instant pid)
                :seon.dev.process/process-group pid
                :seon.dev.process/argv (:seon.dev.process/argv spec)
                :seon.dev.process/environment-digest
                (#'process/environment-digest
                 (:seon.dev.process/environment spec)
                 (:seon.dev.process/argv spec))
                :seon.dev.process/artifact-digest
                @#'process/unpublished-client-digest
                :seon.dev.process/target :seon.dev.target/development
                :seon.dev.process/started-at "test"
                :seon.dev.process/log (str log)}]
    (try
      (fs/create-dirs (fs/parent output))
      (fs/create-dirs (fs/parent runtime))
      (spit (str output) "main-a")
      (spit (str execution-output) "execution-a")
      (spit (str runtime) "runtime-a")
      (spit (str log) (str "[:client] Build completed.\n"
                           "[:execution] Build completed.\n"
                           "[:test] Build completed.\n"))
      (#'process/write-process! configuration process/watcher-id record)
      (let [client-digest (artifact/current-client-digest configuration)
            execution-digest (artifact/current-execution-digest configuration)
            application-digest (apply str (repeat 64 "a"))
            manifest {:seon.dev.artifact/client-digest client-digest
                      :seon.dev.artifact/execution-digest execution-digest
                      :seon.dev.artifact/application-digest application-digest}]
        (is (= process/process-record-schema
               (last (:malli/schema
                      (meta #'process/prepare-watcher!)))))
        (is (= process/process-record-schema
               (last (:malli/schema
                      (meta #'process/admit-watcher-artifact!)))))
        (is (= application-digest
               (:seon.dev.process/artifact-digest
                (process/admit-watcher-artifact! configuration manifest))))
        (#'process/write-process! configuration process/watcher-id record)
        (spit (str execution-output) "execution-b")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"cannot admit"
             (process/admit-watcher-artifact! configuration manifest))))
      (finally (fs/delete-tree directory)))))

(deftest pod-runtime-is-explicitly-selectable-without-changing-the-artifact
  (let [base (test-config)
        configuration
        (target-config base (:seon.dev.test/directory base))
        node-pod (get (process/specs configuration target-manifest)
                      process/pod-id)
        bun-configuration
        (update configuration :seon.dev.config/environment
                assoc "SEON_JS_RUNTIME" "bun")
        bun-pod (get (process/specs bun-configuration target-manifest)
                     process/pod-id)]
    (is (= "node" (first (:seon.dev.process/argv node-pod))))
    (is (= "bun" (first (:seon.dev.process/argv bun-pod))))
    (is (= (rest (:seon.dev.process/argv node-pod))
           (rest (:seon.dev.process/argv bun-pod))))
    (is (= (:seon.dev.process/artifact-digest node-pod)
           (:seon.dev.process/artifact-digest bun-pod)))))

(deftest stale-port-file-does-not-advertise-a-url
  (let [configuration (test-config)
        directory (:seon.dev.test/directory configuration)
        port-file (str (fs/path directory "stale-pod-port"))
        configuration
        (with-default-launch-descriptor
          (assoc (target-config configuration directory)
                 :seon.dev.config/http-port-file port-file))
        manifest target-manifest]
    (try
      (spit port-file "7890")
      (is (nil? (:seon.dev.target/url
                  (process/status configuration manifest))))
      (finally (fs/delete-tree directory)))))

(deftest structured-status-exposes-non-secret-process-identity
  (let [configuration (test-config)
        directory (:seon.dev.test/directory configuration)
        configuration (target-config configuration directory)
        spec (get (process/specs configuration target-manifest)
                  process/watcher-id)
        pid (.pid (java.lang.ProcessHandle/current))
        start-instant (state/process-start-instant pid)
        environment-digest (#'process/environment-digest
                            (:seon.dev.process/environment spec)
                            (:seon.dev.process/argv spec))
        log (str (fs/path directory "watcher.log"))
        record {:seon.dev.process/id process/watcher-id
                :seon.dev.process/pid pid
                :seon.dev.process/start-instant start-instant
                :seon.dev.process/process-group pid
                :seon.dev.process/argv (:seon.dev.process/argv spec)
                :seon.dev.process/environment-digest environment-digest
                :seon.dev.process/artifact-digest
                (:seon.dev.process/artifact-digest spec)
                :seon.dev.process/target :seon.dev.target/development
                :seon.dev.process/started-at "test"
                :seon.dev.process/log log}]
    (try
      (spit log (str "[:client] Build completed.\n"
                     "[:execution] Build completed.\n"
                     "[:test] Build completed.\n"))
      (#'process/write-process! configuration process/watcher-id record)
      (let [status (get-in (process/status configuration target-manifest)
                           [:seon.dev.target/processes process/watcher-id])]
        (is (= pid (:seon.dev.process/pid status)))
        (is (= start-instant (:seon.dev.process/start-instant status)))
        (is (= environment-digest
               (:seon.dev.process/environment-digest status)))
        (is (= (:seon.dev.process/artifact-digest spec)
               (:seon.dev.process/artifact-digest status)))
        (is (not (contains? status :seon.dev.process/environment))
            "status exposes only the digest, never environment values"))
      (finally (fs/delete-tree directory)))))

(deftest artifact-flavor-owns-the-watcher-build-and-cache
  (let [configuration (test-config)
        directory (:seon.dev.test/directory configuration)
        base (target-config configuration directory)
        default-argv (get-in (process/specs base target-manifest)
                             [process/watcher-id :seon.dev.process/argv])
        acme (-> base
                 (assoc :seon.dev.config/client-build-id "acme-client"
                        :seon.dev.config/execution-build-id "acme-execution"
                        :seon.dev.config/execution-output
                        (str (fs/path directory "out-acme/execution/main.js"))
                        :seon.dev.config/artifact-flavor
                        :seon.dev.artifact.flavor/acme
                        :seon.dev.config/shadow-cache-root
                        (str (fs/path directory "shadow-acme")))
                 (assoc-in [:seon.dev.config/environment "SEON_EXTRA_SRC"]
                           (str (fs/path directory "acme")) )
                 (assoc-in [:seon.dev.config/environment "SEON_EXTRA_PRELOAD"]
                           "acme.pod")
                 with-default-launch-descriptor)
        acme-argv (get-in (process/specs
                            acme
                            (assoc target-manifest
                                   :seon.dev.artifact/flavor
                                   :seon.dev.artifact.flavor/acme
                                   :seon.dev.artifact/client-build-id
                                   "acme-client"
                                   :seon.dev.artifact/execution-build-id
                                   "acme-execution"
                                   :seon.dev.artifact/execution-output
                                   (str (fs/path directory
                                                 "out-acme/execution/main.js"))))
                          [process/watcher-id :seon.dev.process/argv])]
    (try
      (is (= ["clj" "-M:cljs" "watch" "client" "execution" "test"]
             default-argv))
      (is (= ["acme-client" "acme-execution"]
             (->> acme-argv
                  (drop-while #(not= "watch" %))
                  rest
                  (take-while #(not= "--config-merge" %))
                  vec))
          "ACME watches only its flavor-owned client build")
      (is (not-any? #{"test"} acme-argv)
          "ACME cannot publish or prune the default test artifact")
      (is (not-any? #(str/includes? % ":cache-root") acme-argv)
          "action config cannot select the Shadow server/cache identity")
      (is (some #(str/includes? % "acme.pod") acme-argv))
      (finally (fs/delete-tree directory)))))

(deftest pod-spec-and-readiness-bind-the-published-bootstrap-root
  (let [configuration (test-config)
        directory (:seon.dev.test/directory configuration)
        runtime-root (fs/path directory "runtime")
        bootstrap-file (fs/path runtime-root "out/bootstrap/example.txt")]
    (try
      (fs/create-dirs (fs/parent bootstrap-file))
      (spit (str bootstrap-file) "published")
      (let [digest (artifact/digest-paths runtime-root ["out/bootstrap"])
            manifest (assoc target-manifest
                            :seon.dev.artifact/runtime-root (str runtime-root)
                            :seon.dev.artifact/bootstrap-digest digest)
            specs (process/specs (target-config configuration directory)
                                 manifest)
            watcher (get specs process/watcher-id)
            pod (get specs process/pod-id)]
        (is (nil? (get-in watcher [:seon.dev.process/environment
                                   "SEON_RUNTIME_ROOT"])))
        (is (= (str runtime-root)
               (get-in pod [:seon.dev.process/environment
                            "SEON_RUNTIME_ROOT"])))
        (is (= digest (:seon.dev.process/bootstrap-digest pod)))
        (is (#'process/runtime-bootstrap-ready? pod))
        (spit (str bootstrap-file) "mutated")
        (is (not (#'process/runtime-bootstrap-ready? pod))
            "readiness rejects bytes that no longer match the manifest"))
      (finally (fs/delete-tree directory)))))

(deftest structured-status-reports-a-foreign-pod-without-advertising-it
  (let [configuration (test-config)
        directory (:seon.dev.test/directory configuration)
        port (with-open [socket (ServerSocket. 0)] (.getLocalPort socket))
        server (shell/process {:out :discard :err :discard
                               :cmd ["python3" "-m" "http.server" (str port)
                                     "--bind" "127.0.0.1"]})
        configuration
        (with-default-launch-descriptor
          (assoc (target-config configuration directory)
                 :seon.dev.config/http-port port))]
    (try
      (loop [remaining 50]
        (let [probe (shell/sh {:continue true :out :string :err :string
                               :cmd ["curl" "-fsS" "-m" "1"
                                     (str "http://127.0.0.1:" port "/")]})]
          (when (and (pos? remaining) (not (zero? (:exit probe))))
            (Thread/sleep 20)
            (recur (dec remaining)))))
      (let [status (process/status configuration target-manifest)]
        (is (= :seon.dev.target.status/ownership-conflict
               (:seon.dev.target/status status)))
        (is (= :seon.dev.process.status/foreign
               (get-in status [:seon.dev.target/processes process/pod-id
                               :seon.dev.process/status])))
        (is (= "test" (:seon.dev.target/cluster-name status)))
        (is (= (:seon.dev.artifact/application-digest target-manifest)
               (get-in status [:seon.dev.target/artifact
                               :seon.dev.artifact/application-digest])))
        (is (nil? (:seon.dev.endpoint/web
                    (:seon.dev.target/endpoints status)))))
      (finally
        (.destroyForcibly ^java.lang.Process (:proc server))
        (fs/delete-tree directory)))))

(deftest process-order-comes-from-dependency-data
  (let [specs {:seon.dev.process/pod
               {:seon.dev.process/dependencies
                [:seon.dev.process/writer :seon.dev.process/watcher]}
               :seon.dev.process/writer
               {:seon.dev.process/dependencies []}
               :seon.dev.process/watcher
               {:seon.dev.process/dependencies []}}]
    (is (= [:seon.dev.process/watcher
            :seon.dev.process/writer
            :seon.dev.process/pod]
           (process/start-order specs)))
    (is (thrown? Exception
                 (process/start-order
                   {:seon.dev.process/a
                    {:seon.dev.process/dependencies [:seon.dev.process/b]}
                    :seon.dev.process/b
                    {:seon.dev.process/dependencies [:seon.dev.process/a]}})))))

(deftest ensure-prepares-default-and-branch-port-parents-before-spawn
  (let [directory (fs/create-temp-dir {:prefix "seon-readiness-parent-"})
        default-port (str (fs/path directory "default-ports/pod.port"))
        branch-port
        (str (fs/path directory "branch-ports/default-proof.port"))
        default-config
        {:seon.dev.config/launch-descriptor
         {::launch/process {::launch/http-port-file default-port}}}
        branch-config
        {:seon.dev.config/launch-descriptor
         {::launch/process {::launch/http-port-file branch-port}}}
        pod-spec {:seon.dev.process/id process/pod-id
                  :seon.dev.process/external-dependencies []}
        spawned (atom [])]
    (try
      (with-redefs-fn
        {#'process/read-process (fn [_ _] nil)
         #'process/converged? (fn [_ _] false)
         #'process/accepting-unmanaged? (fn [_ _] false)
         #'process/spawn-detached!
         (fn [selected _]
           (let [port-file
                 (get-in selected [:seon.dev.config/launch-descriptor
                                   ::launch/process ::launch/http-port-file])]
             (is (fs/directory? (fs/parent port-file))
                 "the descriptor-owned readiness parent exists before spawn")
             (swap! spawned conj port-file)
             {:seon.dev.process/id process/pod-id}))
         #'process/wait-ready! (fn [_ _ record] record)}
        (fn []
          (process/ensure! default-config pod-spec)
          (process/ensure! branch-config pod-spec)))
      (is (= [default-port branch-port] @spawned))
      (let [sibling (str (fs/path (fs/parent branch-port) "other.port"))]
        (spit branch-port "7891")
        (spit sibling "7892")
        (#'process/clear-readiness! branch-config process/pod-id)
        (is (not (fs/exists? branch-port)))
        (is (fs/regular-file? sibling)
            "cleanup preserves sibling readiness files")
        (is (fs/directory? (fs/parent branch-port))
            "cleanup never owns or removes the shared parent"))
      (finally
        (fs/delete-tree directory {:force true})))))

(deftest restore-pod-launch-consumes-the-precommitted-generation
  (let [expected (random-uuid)
        fallback (random-uuid)
        configuration
        (assoc-in
         (test-config)
         [:seon.dev.config/launch-descriptor
          ::launch/restore-startup
          :seon.dev.restore/startup-identity
          :seon.dev.restore/consumer-generations
          process/pod-id]
         expected)]
    (with-redefs [random-uuid (constantly fallback)]
      (is (= expected
             (#'process/selected-process-generation
              configuration process/pod-id)))
      (is (= fallback
             (#'process/selected-process-generation
              configuration process/watcher-id)))
      (is (= fallback
             (#'process/selected-process-generation
              (test-config) process/pod-id))))))

(deftest branch-descriptor-publishes-one-pod-with-real-external-owners
  (let [configuration (test-config)
        directory (:seon.dev.test/directory configuration)
        source-config (target-config configuration directory)
        database-id #uuid "9dcfa740-5f7f-4ff5-ac08-a9c8b605a8aa"
        source-descriptor
        (assoc-in (:seon.dev.config/launch-descriptor source-config)
                  [::launch/database :seon.db.coordinate/attachment]
                  {:seon.db.coordinate/database-id database-id
                   :seon.db.coordinate/branch :db})
        branch-descriptor
        (launch/branch-descriptor
         {::launch/source-descriptor source-descriptor
          ::launch/runtime-cluster "trial"
          ::launch/target-database-name "trial-route"
          ::launch/target-coordinate
          {:seon.db.coordinate/database-id database-id
           :seon.db.coordinate/branch :trial
           :seon.db.coordinate/commit-id
           #uuid "a2bd215f-7ec6-47dc-a627-f8e4948df581"
           :seon.db.coordinate/t 42}
          ::launch/process-dir (str (fs/path directory "trial-process"))
          ::launch/log-dir (str (fs/path directory "trial-logs"))
          ::launch/http-port 0
          ::launch/http-port-file (str (fs/path directory "trial-http.port"))
          ::launch/writable-blob-dir
          (str (fs/path directory "trial-blobs"))})
        branch-config
        (dev-config/select-launch-descriptor source-config branch-descriptor)
        published-descriptor
        (launch/with-execution-artifact
         {::launch/descriptor branch-descriptor
          ::launch/execution-build-id "execution"
          ::launch/execution-output
          (:seon.dev.config/execution-output source-config)
          ::launch/execution-digest
          (:seon.dev.artifact/execution-digest target-manifest)})
        ordinary-specs (process/specs source-config target-manifest)
        branch-specs (process/specs branch-config target-manifest)
        pod (get branch-specs process/pod-id)
        published
        (edn/read-string
         (get-in pod [:seon.dev.process/environment
                      "SEON_LAUNCH_DESCRIPTOR"]))]
    (try
      (is (= process/target-processes
             (process/start-order ordinary-specs)))
      (is (= [process/pod-id] (process/start-order branch-specs)))
      (is (= #{process/pod-id} (set (keys branch-specs))))
      (is (= [] (:seon.dev.process/dependencies pod)))
      (is (= [process/watcher-id process/writer-id]
             (mapv :seon.dev.process/id
                   (:seon.dev.process/external-dependencies pod))))
      (is (every? #(= (:seon.dev.config/process-dir source-config)
                      (:seon.dev.process/owner-process-dir %))
                  (:seon.dev.process/external-dependencies pod)))
      (is (= published-descriptor published))
      (is (not= (#'process/environment-digest
                  (get-in ordinary-specs
                          [process/pod-id :seon.dev.process/environment])
                  (get-in ordinary-specs
                          [process/pod-id :seon.dev.process/argv]))
                (#'process/environment-digest
                  (:seon.dev.process/environment pod)
                  (:seon.dev.process/argv pod))))
      (is (thrown-with-msg?
           Exception #"external process owner is unavailable"
           (process/ensure! branch-config pod)))
      (is (nil? (process/read-process branch-config process/pod-id))
          "dependency rejection occurs before pod publication")
      (let [dependency-ready? (atom true)
            pid (.pid (java.lang.ProcessHandle/current))
            record
            (live-probe-record
             branch-config
             {:seon.dev.process/id process/pod-id
              :seon.dev.process/pid pid
              :seon.dev.process/start-instant
              (state/process-start-instant pid)
              :seon.dev.process/artifact-digest "application"})]
        (with-redefs-fn
          {#'process/read-process
           (fn [_ selected-id]
             (assoc record :seon.dev.process/id selected-id))
           #'process/same-process-spec? (fn [_ _] true)
           #'process/ready? (fn [_ _ _] true)
           #'process/external-dependency-ready?
           (fn [_ _] @dependency-ready?)
           #'process/ownership-conflicts (fn [_ _] [])}
          (fn []
            (let [ready (process/status branch-config target-manifest)]
              (is (= :seon.dev.target.status/ready
                     (:seon.dev.target/status ready)))
              (is (= #{process/watcher-id process/writer-id}
                     (set (keys
                           (:seon.dev.target/external-dependencies ready)))))
              (is (every? :seon.dev.process/ready?
                          (vals (:seon.dev.target/external-dependencies
                                 ready)))))
            (reset! dependency-ready? false)
            (let [degraded (process/status branch-config target-manifest)]
              (is (= :seon.dev.target.status/degraded
                     (:seon.dev.target/status degraded)))
              (is (not-any? :seon.dev.process/ready?
                            (vals (:seon.dev.target/external-dependencies
                                   degraded))))))))
      (finally
        (fs/delete-tree directory)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'seon.dev.process-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
