(ns seon.dev.process-test
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [seon.dev.artifact :as artifact]
            [seon.dev.process :as process]
            [seon.dev.state :as state])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net ServerSocket SocketException]))

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

(defn- target-config [configuration directory]
  (merge configuration
         {:seon.dev.config/client-build-id "client"
          :seon.dev.config/artifact-flavor
          :seon.dev.artifact.flavor/default
          :seon.dev.config/shadow-cache-root
          (str (fs/path directory "shadow"))
          :seon.dev.config/client-output (str (fs/path directory "client.js"))
          :seon.dev.config/writer-output (str (fs/path directory "writer.jar"))
          :seon.dev.config/cluster-dir (str (fs/path directory "cluster"))
          :seon.dev.config/cluster-name "test"
          :seon.dev.config/request-socket (str (fs/path directory "req.sock"))
          :seon.dev.config/publish-socket (str (fs/path directory "pub.sock"))
          :seon.dev.config/writer-repl-port 0
          :seon.dev.config/writer-repl-port-file
          (str (fs/path directory "writer-port"))
          :seon.dev.config/http-port 0
          :seon.dev.config/http-port-file
          (str (fs/path directory "pod-port"))}))

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
        configuration (assoc (target-config configuration directory)
                             :seon.dev.config/http-port-file port-file)
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
                           "acme.pod"))
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
        configuration (assoc (target-config configuration directory)
                              :seon.dev.config/http-port port)]
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

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'seon.dev.process-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
