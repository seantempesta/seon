(ns seon.dev.fresh-operator-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.net ServerSocket]
           [java.util Date]
           [java.util.concurrent TimeUnit]))

(def ^:private project-root
  (.getCanonicalFile (io/file (System/getProperty "user.dir"))))

(defn- fresh-root
  []
  (let [root (io/file project-root "tmp" "fresh-operator-test"
                      (str (random-uuid)))]
    (.mkdirs root)
    root))

(defn- delete-recursively!
  [root]
  (let [temporary-root (.getCanonicalFile (io/file project-root "tmp"))
        target (.getCanonicalFile (io/file root))]
    (when-not (.startsWith (.toPath target) (.toPath temporary-root))
      (throw (ex-info "The test cleanup target is outside project-local tmp/."
                      {:seon.dev.fresh-operator-test/path
                       (.getPath target)})))
    (when (.exists target)
      (doseq [child (reverse (file-seq target))]
        (when-not (.delete ^java.io.File child)
          (throw (ex-info "The test could not delete its fixture path."
                          {:seon.dev.fresh-operator-test/path
                           (.getPath ^java.io.File child)})))))))

(defn- start-disposable-process!
  []
  (.start
   (ProcessBuilder.
    ^java.util.List
    ["/usr/bin/python3" "-c"
     (str "import signal,sys\n"
          "signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))\n"
          "signal.pause()\n")])))

(defn- process-start-date
  [^Process process]
  (let [optional (.startInstant (.info (.toHandle process)))]
    (when-not (.isPresent optional)
      (throw (ex-info "The test child has no start instant." {})))
    (Date/from (.get optional))))

(defn- operator-command
  [root name]
  ["bb"
   "--config" (str (io/file project-root "bb.edn"))
   "--deps-root" (str project-root)
   "--classpath" (str (io/file project-root "script"))
   "-m" "seon.fresh-operator"
   "--seon-root" (str root)
   "stop" name])

(defn- child-environment
  [root]
  (let [code
        (str "(do (require 'seon.fresh-operator)"
             " (let [environment ((var-get (ns-resolve"
             " 'seon.fresh-operator 'child-environment))"
             " (System/getenv \"SEON_FRESH_OPERATOR_TEST_ROOT\"))]"
             " (prn (select-keys environment"
             " [\"SEON_FRESH_OPERATOR_TEST_CREDENTIAL\" \"PATH\"]))))")
        command
        ["bb"
         "--config" (str (io/file project-root "bb.edn"))
         "--deps-root" (str project-root)
         "--classpath" (str (io/file project-root "script"))
         "-e" code]
        builder
        (doto (ProcessBuilder. ^java.util.List command)
          (.directory project-root)
          (.redirectErrorStream true))
        _ (.put (.environment builder)
                "SEON_FRESH_OPERATOR_TEST_ROOT" (str root))
        process (.start builder)
        completed? (.waitFor process 10 TimeUnit/SECONDS)
        _ (when-not completed? (.destroyForcibly process))
        output (str/trim (slurp (.getInputStream process)))]
    (when-not (and completed? (zero? (.exitValue process)))
      (throw
       (ex-info "The fresh operator environment probe failed."
                {:seon.dev.fresh-operator-test/output output})))
    (edn/read-string output)))

(deftest child-environment-loads-dotenv-beneath-shell-overrides
  (let [root (fresh-root)
        dotenv (io/file root ".env")]
    (try
      (spit dotenv
            (str "# parsed as data, not sourced\n"
                 "export SEON_FRESH_OPERATOR_TEST_CREDENTIAL='test-only'\n"
                 "PATH=must-not-replace-the-invoking-path\n"))
      (let [environment (child-environment root)]
        (is (= "test-only"
               (get environment "SEON_FRESH_OPERATOR_TEST_CREDENTIAL")))
        (is (= (System/getenv "PATH") (get environment "PATH"))
            "the invoking environment wins over the repository dotenv"))
      (finally
        (delete-recursively! root)))))

(deftest eval-failure-falls-back-to-sigterm
  (let [root (fresh-root)
        name "eval-failure"
        child (start-disposable-process!)
        server (ServerSocket.
                0 1 (java.net.InetAddress/getLoopbackAddress))
        received (promise)
        served
        (future
          (with-open [socket (.accept server)
                      reader (io/reader socket)
                      writer (io/writer socket)]
            (deliver received (.readLine ^java.io.BufferedReader reader))
            (.write writer
                    (str (pr-str {:tag :ret
                                  :val "nil"
                                  :exception true})
                         "\n"))
            (.flush writer)))]
    (try
      (let [directory (io/file root "data" "clusters" name)
            advertisement
            {:seon.boot/cluster-name name
             :seon.boot/pid (.pid child)
             :seon.boot/start-instant (process-start-date child)
             :seon.boot/prepl-host "127.0.0.1"
             :seon.boot/prepl-port (.getLocalPort server)}
            _ (.mkdirs directory)
            _ (spit (io/file directory "prepl.edn")
                    (pr-str advertisement))
            process
            (.start
             (doto (ProcessBuilder.
                    ^java.util.List (operator-command root name))
               (.directory project-root)
               (.redirectErrorStream true)))
            completed? (.waitFor process 10 TimeUnit/SECONDS)
            _ (when-not completed? (.destroyForcibly process))
            output (slurp (.getInputStream process))
            stop-form (deref received 10000 ::timeout)
            child-stopped? (.waitFor child 10 TimeUnit/SECONDS)]
        (testing "the remote eval exception is a named, loud fallback"
          (is completed? "The operator exceeded ten seconds.")
          (is (= 0 (when completed? (.exitValue process))) output)
          (is (str/includes?
               output
               (str "! prepl unavailable "
                    "(The cluster rejected the prepl operation.); "
                    "SIGTERM pid " (.pid child)
                    " affects shared-JVM clusters: " name))
              output)
          (is (str/includes?
               output
               (str "● " name " stop path=SIGTERM"))
              output))
        (testing "the prepl request and SIGTERM both crossed real boundaries"
          (is (not= ::timeout stop-form))
          (is (str/includes? stop-form "seon.cluster/stop!"))
          (is child-stopped? "The fallback did not stop the advertised PID.")))
      (finally
        (.close server)
        (deref served 1000 nil)
        (when (.isAlive child)
          (.destroyForcibly child)
          (.waitFor child 10 TimeUnit/SECONDS))
        (delete-recursively! root)))))
