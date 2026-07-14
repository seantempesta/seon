(ns seon.db.server-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.db.server :as server])
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
