(ns seon.dev.fresh-operator-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.instrument :as mi]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.instrument :as instrument])
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
  [root & arguments]
  (into
   ["bb"
    "--config" (str (io/file project-root "bb.edn"))
    "--deps-root" (str project-root)
    "--classpath" (str (io/file project-root "script"))
    "-m" "seon.fresh-operator"
    "--seon-root" (str root)]
   arguments))

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

(defn- operator-private-value
  [function-name & arguments]
  (let [code
        (pr-str
         `(do
            (require 'seon.fresh-operator)
            (prn
             (apply
              (var-get
               (ns-resolve 'seon.fresh-operator '~function-name))
              ~(vec arguments)))))
        process
        (.start
         (doto
          (ProcessBuilder.
           ^java.util.List
           ["bb"
            "--config" (str (io/file project-root "bb.edn"))
            "--deps-root" (str project-root)
            "--classpath" (str (io/file project-root "script"))
            "-e" code])
           (.directory project-root)
           (.redirectErrorStream true)))
        completed? (.waitFor process 10 TimeUnit/SECONDS)
        _ (when-not completed? (.destroyForcibly process))
        output (str/trim (slurp (.getInputStream process)))]
    (when-not (and completed? (zero? (.exitValue process)))
      (throw
       (ex-info "The fresh operator parser probe failed."
                {:seon.dev.fresh-operator-test/output output})))
    (edn/read-string output)))

(defn- fresh-process-pre-start
  [root add-form]
  (let [code
        (pr-str
         `(do
            (require 'seon.cluster
                     'seon.instrument
                     'seon.render.value
                     'seon.schema)
            (let [original-resolve# seon.cluster/resolve-bootstrap
                  instances-var#
                  (ns-resolve 'seon.cluster (symbol "running-instances"))]
              (with-redefs
               [seon.cluster/resolve-bootstrap
                (fn [overrides#]
                  (original-resolve#
                   (assoc overrides# :seon.boot/root ~(str root))))]
                (seon.cluster/start!
                 {:seon.boot/cluster-name "anchor"})
                (let [state# (seon.schema/snapshot-state)
                      stale-state#
                      (-> state#
                          (update :seon.schema.state/candidate-forms
                                  dissoc
                                  :seon.render/value)
                          (assoc :seon.schema.state/projection nil))]
                  (seon.schema/restore-state! stale-state#)
                  (try
                    (let [result# (eval (read-string ~add-form))
                          scratch# (get @@instances-var# "scratch")]
                      (prn
                       {:seon.dev.fresh-operator-test/result result#
                        :seon.dev.fresh-operator-test/scratch-ready?
                        (boolean
                         (get-in scratch#
                                 [:seon.boot/advertisement
                                  :seon.render.web/url]))}))
                    (finally
                      (seon.instrument/remove!)
                      (doseq [instance# (vals @@instances-var#)]
                        (seon.cluster/stop! instance#)))))
                (flush)
                (System/exit 0)))))
        process
        (.start
         (doto
          (ProcessBuilder.
           ^java.util.List
           ["clojure" "-M:test" "-e" code])
           (.directory project-root)
           (.redirectErrorStream true)))
        output-future
        (future
          (try
            (slurp (.getInputStream process))
            (catch java.io.IOException error
              (str "The child output stream closed after termination: "
                   (ex-message error)))))
        completed? (.waitFor process 60 TimeUnit/SECONDS)
        _ (when-not completed? (.destroyForcibly process))
        output (deref output-future 10000
                      "The child output reader did not finish.")]
    {:seon.dev.fresh-operator-test/completed? completed?
     :seon.dev.fresh-operator-test/exit
     (when completed? (.exitValue process))
     :seon.dev.fresh-operator-test/output output}))

(deftest config-command-selection-defaults-cluster-and-start-accepts-config
  (is (= {:seon.fresh-operator/name "default"
          :seon.fresh-operator/config-path "config/sparse.edn"}
         (operator-private-value
          'parse-start-arguments ["--config" "config/sparse.edn"])))
  (is (= {:seon.fresh-operator/name "alpha"
          :seon.fresh-operator/config-path "config/sparse.edn"}
         (operator-private-value
          'parse-start-arguments ["alpha" "--config" "config/sparse.edn"])))
  (is (= {:seon.fresh-operator/name "default"
          :seon.fresh-operator/config-path "config/sparse.edn"}
         (operator-private-value
          'parse-config-apply-arguments ["config/sparse.edn"])))
  (is (= {:seon.fresh-operator/name "beta"
          :seon.fresh-operator/config-path "config/sparse.edn"}
         (operator-private-value
          'parse-config-apply-arguments
          ["beta" "config/sparse.edn"])))
  (is (= {:seon.fresh-operator/name "beta"
          :seon.fresh-operator/force? true}
         (operator-private-value
          'parse-stop-arguments ["--force" "beta"]))))

(deftest add-refreshes-a-genuinely-stale-wrapper-before-current-start
  (let [form (operator-private-value 'add-form "scratch" {})
        start-var #'cluster/start!
        start-meta (meta start-var)
        instances-var
        (ns-resolve 'seon.cluster (symbol "running-instances"))
        instances-before @(var-get instances-var)
        connection (atom nil)
        current-request
        {:seon.boot/cluster-name "scratch"
         :seon.config/manifest {}}
        stale-schema
        [:=> [:cat
              [:map {:closed true}
               [:seon.boot/cluster-name :string]]]
         :map]
        current-schema
        [:=> [:cat
              [:map {:closed true}
               [:seon.boot/cluster-name :string]
               [:seon.config/manifest :map]]]
         :map]
        start-calls (atom [])
        start-filter (mi/-filter-var #{start-var})
        apply-current!
        (fn [_]
          (mi/clj-collect! {:ns ['seon.cluster]})
          (mi/instrument! {:filters [start-filter]})
          {:seon.instrument/registered 1
           :seon.instrument/instrumented 1})]
    (try
      (with-redefs
       [cluster/start!
        (fn [request]
          (swap! start-calls conj request)
          {:seon.boot/cluster-connection connection})
        config/effective
        (fn [_ _]
          {:seon.config/on-core-error :panic})
        instrument/apply! apply-current!]
        (alter-meta! start-var assoc :malli/schema stale-schema)
        (mi/clj-collect! {:ns ['seon.cluster]})
        (mi/instrument! {:filters [start-filter]})
        (alter-meta! start-var assoc :malli/schema current-schema)
        (is (thrown? Exception (cluster/start! current-request))
            "the installed wrapper still enforces the old closed request")
        (reset!
         (var-get instances-var)
         {"live"
          {:seon.boot/cluster-connection connection
           :seon.boot/advertisement
           {:seon.boot/cluster-name "live"}}})
        (is (= "scratch" (eval (read-string form))))
        (is (= [current-request] @start-calls)
            "the pre-start apply! replaced the stale wrapper before start"))
      (finally
        (mi/unstrument! {:filters [start-filter]})
        (alter-meta! start-var (constantly start-meta))
        (reset! (var-get instances-var) instances-before)))))

(deftest fresh-process-loads-schema-before-refresh-and-start
  (let [root (fresh-root)]
    (try
      (let [{::keys [completed? exit output]}
            (fresh-process-pre-start
             root
             (operator-private-value 'add-form "scratch" {}))]
        (is completed? "the fresh-process pre-start exceeded sixty seconds")
        (is (= 0 exit) output)
        (is
         (=
          {::result "scratch"
           ::scratch-ready? true}
          (some
           (fn [line]
             (when (str/starts-with?
                    line
                    "#:seon.dev.fresh-operator-test")
               (edn/read-string line)))
           (str/split-lines output)))
         output))
      (finally
        (delete-recursively! root)))))

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
                    ^java.util.List (operator-command root "stop" name))
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

(deftest eval-failure-refuses-to-sigterm-a-shared-jvm-without-force
  (let [root (fresh-root)
        name "shared-target"
        sibling "shared-sibling"
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
      (let [start-instant (process-start-date child)
            target-advertisement
            {:seon.boot/cluster-name name
             :seon.boot/pid (.pid child)
             :seon.boot/start-instant start-instant
             :seon.boot/prepl-host "127.0.0.1"
             :seon.boot/prepl-port (.getLocalPort server)}
            sibling-advertisement
            (assoc target-advertisement
                   :seon.boot/cluster-name sibling
                   :seon.boot/prepl-port (inc (.getLocalPort server)))]
        (doseq [[cluster advertisement]
                [[name target-advertisement]
                 [sibling sibling-advertisement]]]
          (let [directory (io/file root "data" "clusters" cluster)]
            (.mkdirs directory)
            (spit (io/file directory "prepl.edn")
                  (pr-str advertisement))))
        (let [process
              (.start
               (doto
                (ProcessBuilder.
                 ^java.util.List (operator-command root "stop" name))
                 (.directory project-root)
                 (.redirectErrorStream true)))
              completed? (.waitFor process 10 TimeUnit/SECONDS)
              _ (when-not completed? (.destroyForcibly process))
              output (slurp (.getInputStream process))
              stop-form (deref received 10000 ::timeout)]
          (is completed? "the refusal exceeded ten seconds")
          (is (= 1 (when completed? (.exitValue process))) output)
          (is (str/includes? output "Refusing SIGTERM for shared JVM")
              output)
          (is (str/includes? output sibling) output)
          (is (str/includes? output (str "stop --force " name)) output)
          (is (.isAlive child)
              "the target and sibling process survived the refused fallback")
          (is (not= ::timeout stop-form))
          (is (str/includes? stop-form "seon.cluster/stop!"))))
      (finally
        (.close server)
        (deref served 1000 nil)
        (when (.isAlive child)
          (.destroyForcibly child)
          (.waitFor child 10 TimeUnit/SECONDS))
        (delete-recursively! root)))))
