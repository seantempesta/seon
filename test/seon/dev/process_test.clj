(ns seon.dev.process-test
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [seon.dev.process :as process]
            [seon.dev.state :as state])
  (:import [java.net ServerSocket]))

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
            ambient-record (process/ensure! configuration ambient-noise-spec)]
        (is (= (:seon.dev.process/pid first-record)
               (:seon.dev.process/pid second-record)))
        (is (state/process-identity-alive? first-record))
        (is (= (:seon.dev.process/pid first-record)
               (:seon.dev.process/process-group first-record)))
        (is (= (:seon.dev.process/pid first-record)
               (:seon.dev.process/pid ambient-record))
            "ambient operator variables do not restart the managed process")
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
        log (fs/path (:seon.dev.test/directory configuration) "watcher.log")
        pid (.pid (java.lang.ProcessHandle/current))
        record {:seon.dev.process/id process/watcher-id
                :seon.dev.process/pid pid
                :seon.dev.process/start-instant (state/process-start-instant pid)
                :seon.dev.process/log (str log)}
        spec {:seon.dev.process/id process/watcher-id
              :seon.dev.process/readiness :seon.dev.process.readiness/watcher}]
    (try
      (spit (str log) "[client] Compiling ...\n[client] Build completed.\n")
      (is (process/ready? configuration spec record))
      (spit (str log) "[client] Compiling ...\n" :append true)
      (is (not (process/ready? configuration spec record)))
      (spit (str log) "[client] Build failure:\n" :append true)
      (is (not (process/ready? configuration spec record)))
      (spit (str log) "[client] Build completed.\n" :append true)
      (is (process/ready? configuration spec record))
      (finally (fs/delete-tree (:seon.dev.test/directory configuration))))))

(deftest stale-port-file-does-not-advertise-a-url
  (let [configuration (test-config)
        directory (:seon.dev.test/directory configuration)
        port-file (str (fs/path directory "stale-pod-port"))
        configuration
        (merge configuration
               {:seon.dev.config/client-output (str (fs/path directory "client.js"))
                :seon.dev.config/writer-output (str (fs/path directory "writer.jar"))
                :seon.dev.config/cluster-dir (str (fs/path directory "cluster"))
                :seon.dev.config/cluster-name "test"
                :seon.dev.config/request-socket (str (fs/path directory "req.sock"))
                :seon.dev.config/publish-socket (str (fs/path directory "pub.sock"))
                :seon.dev.config/writer-repl-port 0
                :seon.dev.config/writer-repl-port-file
                (str (fs/path directory "writer-port"))
                :seon.dev.config/http-port 0
                :seon.dev.config/http-port-file port-file})
        manifest {:seon.dev.artifact/client-digest "client"
                  :seon.dev.artifact/writer-digest "writer"
                  :seon.dev.artifact/application-digest "application"}]
    (try
      (spit port-file "7890")
      (is (nil? (:seon.dev.target/url
                  (process/status configuration manifest))))
      (finally (fs/delete-tree directory)))))

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
