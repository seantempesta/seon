(ns seon.db.server-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db.registry :as registry]
            [seon.db.server :as server]
            [seon.schema :as schema])
  (:import [java.io PushbackReader]
           [java.net InetAddress Socket]))

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
