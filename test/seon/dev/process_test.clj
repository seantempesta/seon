(ns seon.dev.process-test
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
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

(defn- harmless-spec [id argv]
  {:seon.dev.process/id id
   :seon.dev.process/argv argv
   :seon.dev.process/environment (into {} (System/getenv))
   :seon.dev.process/dependencies []
   :seon.dev.process/readiness :seon.dev.process.readiness/process
   :seon.dev.process/ready-timeout-ms 5000
   :seon.dev.process/artifact-digest "test-artifact"})

(defn- with-default-launch-descriptor [target]
  (dev-config/select-launch-descriptor
   target
   (launch/default-descriptor
    {::launch/cluster-dir (:seon.dev.config/cluster-dir target)
     ::launch/artifact-flavor
     (:seon.dev.config/artifact-flavor target)
     ::launch/client-build-id
     (:seon.dev.config/client-build-id target)
     ::launch/request-socket-path
     (:seon.dev.config/request-socket target)
     ::launch/publish-socket-path
     (:seon.dev.config/publish-socket target)
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
                :seon.dev.config/artifact-flavor
                :seon.dev.artifact.flavor/default
                :seon.dev.config/source-checkout? true
                :seon.dev.config/shadow-cache-root
                (str (fs/path directory "shadow"))
                :seon.dev.config/client-output
                (str (fs/path directory "client.js"))
                :seon.dev.config/writer-output
                (str (fs/path directory "writer.jar"))
                :seon.dev.config/artifact-manifest
                (str (fs/path directory "artifact.edn"))
                :seon.dev.config/cluster-dir
                (str (fs/path directory "test"))
                :seon.dev.config/cluster-name "test"
                :seon.dev.config/request-socket
                (str (fs/path directory "req.sock"))
                :seon.dev.config/publish-socket
                (str (fs/path directory "pub.sock"))
                :seon.dev.config/writer-repl-port 0
                :seon.dev.config/writer-repl-port-file
                (str (fs/path directory "writer-port"))
                :seon.dev.config/http-port 0
                :seon.dev.config/http-port-file
                (str (fs/path directory "pod-port"))})))

(def target-manifest
  {:seon.dev.artifact/flavor :seon.dev.artifact.flavor/default
   :seon.dev.artifact/client-build-id "client"
   :seon.dev.artifact/client-digest "client"
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
            "print('[:test] Build completed.',flush=True)\n"
            "time.sleep(300)\n")]
      :seon.dev.process/environment (into {} (System/getenv))
      :seon.dev.process/dependencies []
      :seon.dev.process/readiness :seon.dev.process.readiness/watcher
      :seon.dev.process/ready-timeout-ms 310000
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
            spawned-pid (some-> (slurp (str pid-file)) str/trim parse-long)]
        (is (= 130 (:exit result)))
        (is (pos-int? spawned-pid) "the main thread completed a real spawn")
        (is (nil? (state/process-start-instant spawned-pid))
            "the hook waited for publication and drained the process")
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
        (is (= (:seon.dev.process/pid first-record)
               (:seon.dev.process/process-group first-record)))
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
      (process/stop! configuration id)
      (is (.isAlive ^java.lang.Process (:proc innocent)))
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
      (process/stop! configuration process/pod-id)
      (is (fs/regular-file? port-file))
      (finally
        (.destroyForcibly ^java.lang.Process (:proc server))
        (cleanup! configuration [process/pod-id])))))

(deftest latest-watcher-result-is-readiness-truth
  (let [configuration (test-config)
        configuration
        (assoc configuration
               :seon.dev.config/client-build-id "client"
               :seon.dev.config/artifact-flavor
               :seon.dev.artifact.flavor/default)
        log (fs/path (:seon.dev.test/directory configuration) "watcher.log")
        pid (.pid (java.lang.ProcessHandle/current))
        record {:seon.dev.process/id process/watcher-id
                :seon.dev.process/pid pid
                :seon.dev.process/start-instant (state/process-start-instant pid)
                :seon.dev.process/log (str log)}
        spec {:seon.dev.process/id process/watcher-id
              :seon.dev.process/readiness :seon.dev.process.readiness/watcher}]
    (try
      (spit (str log) (str "[:client] Compiling ...\n"
                           "[:client] Build completed.\n"
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
        record {:seon.dev.process/id process/pod-id
                :seon.dev.process/pid pid
                :seon.dev.process/start-instant (state/process-start-instant pid)}
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
               :seon.dev.config/artifact-flavor
               :seon.dev.artifact.flavor/acme)
        log (fs/path (:seon.dev.test/directory configuration) "watcher.log")
        pid (.pid (java.lang.ProcessHandle/current))
        record {:seon.dev.process/id process/watcher-id
                :seon.dev.process/pid pid
                :seon.dev.process/start-instant (state/process-start-instant pid)
                :seon.dev.process/log (str log)}
        spec {:seon.dev.process/id process/watcher-id
              :seon.dev.process/readiness :seon.dev.process.readiness/watcher}]
    (try
      (spit (str log) "[:acme-client] Build completed.\n")
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
        runtime (fs/path directory
                         "shadow-acme/builds/acme-client/dev/out/cljs-runtime/a.js")
        configuration
        (assoc configuration
               :seon.dev.config/client-build-id "acme-client"
               :seon.dev.config/artifact-flavor
               :seon.dev.artifact.flavor/acme
               :seon.dev.config/client-output (str output)
               :seon.dev.config/shadow-cache-root
               (str (fs/path directory "shadow-acme")))
        log (fs/path directory "watcher.log")
        pid (.pid (java.lang.ProcessHandle/current))
        record {:seon.dev.process/id process/watcher-id
                :seon.dev.process/pid pid
                :seon.dev.process/start-instant (state/process-start-instant pid)
                :seon.dev.process/log (str log)}]
    (try
      (fs/create-dirs (fs/parent output))
      (fs/create-dirs (fs/parent runtime))
      (spit (str output) "main-a")
      (spit (str runtime) "runtime-a")
      (spit (str log) "[:acme-client] Build completed.\n")
      (let [digest (artifact/current-client-digest configuration)
            spec {:seon.dev.process/id process/watcher-id
                  :seon.dev.process/readiness
                  :seon.dev.process.readiness/watcher
                  :seon.dev.process/artifact-digest digest}]
        (is (process/ready? configuration spec record))
        (spit (str runtime) "runtime-b")
        (is (not (process/ready? configuration spec record))
            "successful hot reload bytes cannot retain the old identity"))
      (finally (fs/delete-tree directory)))))

(deftest stale-port-file-does-not-advertise-a-url
  (let [configuration (test-config)
        directory (:seon.dev.test/directory configuration)
        port-file (str (fs/path directory "stale-pod-port"))
        configuration
        (with-default-launch-descriptor
          (assoc (target-config configuration directory)
                 :seon.dev.config/http-port-file port-file))
        manifest {:seon.dev.artifact/client-digest "client"
                  :seon.dev.artifact/writer-digest "writer"
                  :seon.dev.artifact/application-digest "application"}]
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
                            (:seon.dev.process/environment spec))
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
      (spit log "[:client] Build completed.\n")
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
                                   "acme-client"))
                          [process/watcher-id :seon.dev.process/argv])]
    (try
      (is (= ["clj" "-M:cljs" "watch" "client" "test"] default-argv))
      (is (= ["acme-client"]
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
      (is (= branch-descriptor published))
      (is (not= (#'process/environment-digest
                  (get-in ordinary-specs
                          [process/pod-id :seon.dev.process/environment]))
                (#'process/environment-digest
                  (:seon.dev.process/environment pod))))
      (is (thrown-with-msg?
           Exception #"external process owner is unavailable"
           (process/ensure! branch-config pod)))
      (is (nil? (process/read-process branch-config process/pod-id))
          "dependency rejection occurs before pod publication")
      (finally
        (fs/delete-tree directory)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'seon.dev.process-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
