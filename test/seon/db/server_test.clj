(ns seon.db.server-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.server :as server]
            [seon.schema :as schema])
  (:import [java.io PushbackReader]
           [java.net InetAddress Socket]
           [java.util.concurrent TimeUnit]))

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))] (.delete file)))

(defn- wait-for! [predicate timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (predicate) true
        (< (System/nanoTime) deadline) (do (Thread/sleep 25) (recur))
        :else false))))

(defn- release-result [released?]
  (cond->
   {::registry/database-name :terminal-test
    ::registry/attachment
    {::coordinate/database-id #uuid "2965429e-ea9d-4262-b20c-30480f0b090b"
     ::coordinate/branch :db}
    ::registry/coordinate
    {::coordinate/database-id #uuid "2965429e-ea9d-4262-b20c-30480f0b090b"
     ::coordinate/branch :db
     ::coordinate/commit-id #uuid "dd3a7cc2-0798-466a-b734-5fec95bb69e2"
     ::coordinate/t 536870913}
    ::registry/released? released?}
    (not released?)
    (assoc ::registry/release-error "injected release failure")))

(defn- stop-response [stopped?]
  {::server/stopped? stopped?
   ::server/release-results [(release-result stopped?)]})

(deftest writer-terminal-result-schema-is-closed-and-portable
  (let [generation "6d295410-5883-4d9f-a532-8f7b71b9812a"
        completed
        {:seon.db.terminal/generation generation
         :seon.db.terminal/process protocol/writer-process
         :seon.db.terminal/completed? true
         :seon.db.terminal/stop-response (stop-response false)}
        failed
        {:seon.db.terminal/generation generation
         :seon.db.terminal/process protocol/writer-process
         :seon.db.terminal/completed? false
         :seon.db.terminal/stop-error "injected stop failure"}]
    (is (protocol/valid-writer-terminal-result? completed))
    (is (protocol/valid-writer-terminal-result? failed))
    (is (false? (protocol/valid-writer-terminal-result?
                 (assoc completed :seon.db.terminal/stop-error "crossed"))))
    (is (false? (protocol/valid-writer-terminal-result?
                 (assoc failed :seon.db.terminal/stop-response
                        (stop-response true)))))
    (is (false? (protocol/valid-writer-terminal-result?
                 (assoc completed :seon.db.terminal/generation "not-a-uuid"))))))

(deftest terminal-configuration-requires-the-paired-managed-values
  (let [generation "6d295410-5883-4d9f-a532-8f7b71b9812a"
        result-path "tmp/terminal-result.edn"]
    (is (nil? (#'server/terminal-configuration {})))
    (is (= {::server/generation generation ::server/result-path result-path}
           (#'server/terminal-configuration
            {"SEON_PROCESS_GENERATION" generation
             "SEON_APPLICATION_RESULT_PATH" result-path})))
    (doseq [environment
            [{"SEON_PROCESS_GENERATION" generation}
             {"SEON_APPLICATION_RESULT_PATH" result-path}
             {"SEON_PROCESS_GENERATION" "not-a-uuid"
              "SEON_APPLICATION_RESULT_PATH" result-path}]]
      (try
        (#'server/terminal-configuration environment)
        (is false "partial or malformed terminal configuration must fail")
        (catch clojure.lang.ExceptionInfo error
          (is (= :configuration (:seon.error/kind (ex-data error)))))))))

(deftest terminal-publisher-is-atomic-and-first-result-wins
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "seon-writer-terminal" (make-array java.nio.file.attribute.FileAttribute 0)))
        result-file (io/file directory "application.edn")
        generation "6d295410-5883-4d9f-a532-8f7b71b9812a"
        completed
        {:seon.db.terminal/generation generation
         :seon.db.terminal/process protocol/writer-process
         :seon.db.terminal/completed? true
         :seon.db.terminal/stop-response (stop-response true)}
        failed
        {:seon.db.terminal/generation generation
         :seon.db.terminal/process protocol/writer-process
         :seon.db.terminal/completed? false
         :seon.db.terminal/stop-error "must not replace the winner"}
        publish! (#'server/terminal-publisher
                  {::server/result-path (.getPath result-file)})]
    (try
      (is (= completed (publish! completed)))
      (is (nil? (publish! failed)))
      (is (= completed (edn/read-string (slurp result-file))))
      (is (empty? (filter #(re-find #"\\.tmp$" (.getName %))
                          (file-seq directory))))
      (finally
        (delete-tree! directory)))))

(deftest managed-shutdown-publishes-completed-and-failed-variants
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "seon-writer-terminal-result" (make-array java.nio.file.attribute.FileAttribute 0)))
        generation "6d295410-5883-4d9f-a532-8f7b71b9812a"]
    (try
      (doseq [[filename stop-behavior expected-completed?]
              [["release-failed.edn" (constantly (stop-response false)) true]
               ["stop-threw.edn"
                (fn [_] (throw (ex-info (apply str (repeat 5000 "x")) {})))
                false]]]
        (let [result-file (io/file directory filename)
              configuration {::server/generation generation
                             ::server/result-path (.getPath result-file)}
              returned (with-redefs [server/stop! stop-behavior]
                         (#'server/run-shutdown! {} configuration))
              published (edn/read-string (slurp result-file))]
          (is (= returned published))
          (is (= expected-completed?
                 (:seon.db.terminal/completed? published)))
          (is (protocol/valid-writer-terminal-result? published))))
      (let [failed (edn/read-string
                    (slurp (io/file directory "stop-threw.edn")))]
        (is (= 4096 (count (:seon.db.terminal/stop-error failed)))))
      (finally
        (delete-tree! directory)))))

(deftest real-writer-process-publishes-its-successful-terminal-result
  (let [directory (doto (io/file "tmp" (str "wt-" (random-uuid)))
                    .mkdirs)
        request-socket (io/file directory "request.sock")
        publish-socket (io/file directory "publish.sock")
        result-file (io/file directory "application.edn")
        log-file (io/file directory "writer.log")
        generation (str (random-uuid))
        java (io/file (System/getProperty "java.home") "bin" "java")
        command [(str java)
                 "--add-modules" "jdk.incubator.vector"
                 "-cp" (System/getProperty "java.class.path")
                 "clojure.main" "-m" "seon.db.server"
                 "--backend" "memory"
                 "--db-name" (str "terminal-process-" (random-uuid))
                 "--req-sock" (.getPath request-socket)
                 "--pub-sock" (.getPath publish-socket)]
        builder (ProcessBuilder. ^java.util.List command)
        environment (.environment builder)]
    (.put environment "SEON_PROCESS_GENERATION" generation)
    (.put environment "SEON_APPLICATION_RESULT_PATH" (.getPath result-file))
    (.redirectErrorStream builder true)
    (.redirectOutput builder log-file)
    (let [process (.start builder)]
      (try
        (is (wait-for! #(.exists request-socket) 15000)
            (str "writer did not become ready; log="
                 (when (.exists log-file) (slurp log-file))))
        (.destroy process)
        (is (.waitFor process 20 TimeUnit/SECONDS)
            "the TERM-triggered shutdown hook must complete")
        (is (wait-for! #(.exists result-file) 1000)
            "the hook publishes before the writer exits")
        (when (.exists result-file)
          (let [result (edn/read-string (slurp result-file))]
            (is (protocol/valid-writer-terminal-result? result))
            (is (= generation (:seon.db.terminal/generation result)))
            (is (= protocol/writer-process
                   (:seon.db.terminal/process result)))
            (is (true? (:seon.db.terminal/completed? result)))
            (is (true? (get-in result
                               [:seon.db.terminal/stop-response
                                ::server/stopped?])))
            (let [release-results
                  (get-in result [:seon.db.terminal/stop-response
                                  ::server/release-results])]
              (is (seq release-results))
              (is (every? ::registry/released? release-results)))))
        (finally
          (when (.isAlive process)
            (.destroyForcibly process)
            (.waitFor process 5 TimeUnit/SECONDS))
          (delete-tree! directory))))))

(deftest writer-publishes-loopback-io-prepl-port
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "seon-writer-prepl" (make-array java.nio.file.attribute.FileAttribute 0)))
        port-file (io/file directory "writer.port")
        request-socket (io/file directory "request.sock")
        publish-socket (io/file directory "publish.sock")
        runtime (server/start! ["--backend" "memory"
                                "--db-name" (str "prepl-" (random-uuid))
                                "--req-sock" (.getPath request-socket)
                                "--pub-sock" (.getPath publish-socket)
                                "--repl-port" "0"
                                "--repl-port-file" (.getPath port-file)])]
    (try
      (let [port (parse-long (.trim (slurp port-file)))]
        (is (pos-int? port))
        (with-open [socket (Socket. (InetAddress/getLoopbackAddress) port)
                    writer (io/writer socket)
                    reader (PushbackReader. (io/reader socket))]
          (.write writer "41\n")
          (.flush writer)
          (is (= {:tag :ret :val "41" :ns "user" :ms 0 :form "41"}
                 (assoc (edn/read reader) :ms 0)))
          (.write writer "(inc *1)\n")
          (.flush writer)
          (is (= "42" (:val (edn/read reader))))))
      (finally
        (server/stop! runtime)
        (is (not (.exists port-file)))
        (doseq [file (reverse (file-seq directory))] (.delete file))))))

(deftest server-stop-does-not-claim-success-after-writer-release-failure
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        directory (.toFile (java.nio.file.Files/createTempDirectory
                            "seon-writer-stop" (make-array java.nio.file.attribute.FileAttribute 0)))
        database-name (str "server-release-failure-" (random-uuid))
        request-socket (io/file directory "request.sock")
        publish-socket (io/file directory "publish.sock")
        runtime
        (server/start! ["--backend" "memory"
                        "--db-name" database-name
                        "--req-sock" (.getPath request-socket)
                        "--pub-sock" (.getPath publish-socket)])
        before
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})]
    (try
      (let [result
            (with-redefs [d/release
                          (fn [_]
                            (throw (ex-info "server release failed" {})))]
              (server/stop! runtime))
            failure (first (::server/release-results result))]
        (is (false? (::server/stopped? result)))
        (is (schema/valid-candidate-value? ::server/stop-response result))
        (is (= (keyword database-name)
               (::registry/database-name failure)))
        (is (= (::registry/attachment before)
               (::registry/attachment failure)))
        (is (= (::registry/coordinate before)
               (::registry/coordinate failure)))
        (is (false? (::registry/released? failure)))
        (is (re-find #"server release failed"
                     (::registry/release-error failure))))
      (finally
        (registry/restore-registry! {::registry/snapshot snapshot})
        (server/stop! runtime)
        (doseq [file (reverse (file-seq directory))] (.delete file))))))
