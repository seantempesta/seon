(ns seon.dev.fresh-operator-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.instrument :as mi]
            [seon.cluster :as cluster]
            [seon.cluster.source :as source]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.instrument :as instrument]
            [seon.operator.state :as operator.state]
            [seon.test-support :as test-support])
  (:import [java.net ServerSocket]
           [java.util Date]
           [java.util.concurrent CompletableFuture ExecutionException
            TimeUnit TimeoutException]
           [java.util.function Function Supplier]))

(def ^:private project-root
  (.getCanonicalFile (io/file (System/getProperty "user.dir"))))

(def ^:private operator-classpath
  (str (io/file project-root "script")
       java.io.File/pathSeparator
       (io/file project-root "resources")))

(defn- fresh-root
  []
  (let [root (io/file project-root "tmp" "fresh-operator-test"
                      (str (random-uuid)))]
    (.mkdirs root)
    root))

(defn- delete-recursively!
  [root]
  (test-support/delete-recursively! root))

(defn- start-disposable-process!
  []
  (.start
   (ProcessBuilder.
    ^java.util.List
    ["/usr/bin/python3" "-c"
     (str "import signal,sys\n"
          "signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))\n"
          "signal.pause()\n")])))

(defn- start-sigterm-resistant-process!
  []
  (let [process
        (.start
         (doto
          (ProcessBuilder.
           ^java.util.List
           ["/usr/bin/python3" "-c"
            (str "import signal\n"
                 "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                 "print('ready',flush=True)\n"
                 "signal.pause()\n")])
          (.redirectErrorStream true)))]
    (when-not (= "ready"
                 (.readLine ^java.io.BufferedReader
                            (io/reader (.getInputStream process))))
      (throw (ex-info "The SIGTERM-resistant child did not become ready."
                      {})))
    process))

(defn- start-disposable-process-tree!
  []
  (.start
   (doto
    (ProcessBuilder.
     ^java.util.List
     ["/usr/bin/python3" "-c"
      (str "import signal,subprocess,sys\n"
           "child=subprocess.Popen([sys.executable,'-c',"
           "'import signal; signal.pause()'])\n"
           "print(child.pid,flush=True)\n"
           "signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))\n"
           "signal.pause()\n")])
    (.redirectErrorStream true))))

(defn- process-start-date
  [^Process process]
  (let [optional (.startInstant (.info (.toHandle process)))]
    (when-not (.isPresent optional)
      (throw (ex-info "The test child has no start instant." {})))
    (Date/from (.get optional))))

(defn- child-process-record
  [root ^Process child]
  {:seon.dev.process/generation (random-uuid)
   :seon.dev.process/pid (.pid child)
   :seon.dev.process/start-instant
   (str (.toInstant (process-start-date child)))
   :seon.dev.process/root (.getCanonicalPath (io/file root))
   :seon.dev.process/log
   (str (io/file root "data" "clusters" "logs"
                 (str (.pid child) ".log")))})

(defn- process-claim-file
  [generation]
  (io/file project-root "data" "operator" "claims" "processes"
           (str generation ".edn")))

(defn- advertisement-process-identity
  [root name]
  (select-keys
   (edn/read-string
    (slurp (io/file root "data" "clusters" name "prepl.edn")))
   [:seon.boot/pid :seon.boot/start-instant]))

(defn- reap-process-identity!
  [{:seon.boot/keys [pid start-instant]}]
  (when-let [^java.lang.ProcessHandle handle
             (some-> (java.lang.ProcessHandle/of (long pid))
                     (.orElse nil))]
    (let [current-start (.startInstant (.info handle))]
      (when (and (.isAlive handle)
                 (.isPresent current-start)
                 (= (.toEpochMilli (.get current-start))
                    (.getTime ^Date start-instant)))
        (.destroy handle)
        (try
          (.get (.onExit handle) 10 TimeUnit/SECONDS)
          (catch TimeoutException _
            (.destroyForcibly handle)
            (.get (.onExit handle) 10 TimeUnit/SECONDS))))))
  nil)

(defn- operator-command
  [root & arguments]
  (into
    ["bb"
    "--config" (str (io/file project-root "bb.edn"))
    "--deps-root" (str project-root)
    "--classpath" operator-classpath
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
         "--classpath" operator-classpath
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
    (edn/read-string (last (str/split-lines output)))))

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
            "--classpath" operator-classpath
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
    (edn/read-string (last (str/split-lines output)))))

(defn- operator-private-outcome
  [function-name & arguments]
  (let [code
        (pr-str
         `(do
            (require 'seon.fresh-operator)
            (prn
             (try
               {:seon.dev.fresh-operator-test/value
                (apply
                 (var-get
                  (ns-resolve 'seon.fresh-operator '~function-name))
                 ~(vec arguments))}
               (catch Throwable failure#
                 {:seon.dev.fresh-operator-test/message
                  (ex-message failure#)
                  :seon.dev.fresh-operator-test/data
                  (ex-data failure#)})))))
        process
        (.start
         (doto
          (ProcessBuilder.
           ^java.util.List
           ["bb"
            "--config" (str (io/file project-root "bb.edn"))
            "--deps-root" (str project-root)
            "--classpath" operator-classpath
            "-e" code])
           (.directory project-root)
           (.redirectErrorStream true)))
        completed? (.waitFor process 10 TimeUnit/SECONDS)
        _ (when-not completed? (.destroyForcibly process))
        output (str/trim (slurp (.getInputStream process)))]
    (when-not (and completed? (zero? (.exitValue process)))
      (throw
       (ex-info "The fresh operator outcome probe failed."
                {:seon.dev.fresh-operator-test/output output})))
    (edn/read-string (last (str/split-lines output)))))

(defn- cold-start-calls
  [root]
  (let [code
        (pr-str
         `(do
            (require 'seon.fresh-operator)
            (let [calls# (atom [])
                  start-instant# (java.util.Date. 1000)
                  generation# (random-uuid)
                  operator-var#
                  (fn [name#]
                    (ns-resolve 'seon.fresh-operator name#))
                  advertisement#
                  {:seon.boot/cluster-name "cold-start"
                   :seon.boot/pid 42
                   :seon.boot/start-instant start-instant#
                   :seon.render.web/url "http://127.0.0.1:7994"
                   :seon.boot/prepl-port 7993}]
              (with-redefs-fn
                {(operator-var# (symbol "source-observations"))
                 (fn [_#]
                   {:seon.fresh-operator/advertisements []
                    :seon.fresh-operator/jvms []})
                 (operator-var# (symbol "offline-roster"))
                 (fn [_#]
                   (swap! calls# conj :offline-roster)
                   (throw
                    (ex-info "cold start read the offline roster" {})))
                 (operator-var# (symbol "ensure-dependency-cache!"))
                 (fn []
                   (swap! calls# conj :dependency-cache)
                   {:seon.dev-cache/path
                    ~(.getCanonicalPath
                      (io/file project-root
                               "target/dev-dependency-classes/test"))})
                 (operator-var# (symbol "launch!"))
                 (fn [_root# name# _manifest# _ready-port# _adoption-port#
                      _silence-ms# _cache-path#]
                   (swap! calls# conj [:launch name#])
                   {:seon.fresh-operator/pid 42})
                 (operator-var# (symbol "record-launched-process!"))
                 (fn [_root# _adoption-server# _silence-ms# _launch-result#]
                   {:seon.dev.process/generation generation#
                    :seon.dev.process/pid 42
                    :seon.dev.process/start-instant
                    (str (.toInstant start-instant#))
                    :seon.dev.process/root ~(str root)
                    :seon.dev.process/log "test.log"})
                 (operator-var# (symbol "await-advertisement!"))
                 (fn [_root# _name# _pid# _ready-server# _silence-ms#]
                   advertisement#)
                 (operator-var# (symbol "print-started!"))
                 (fn [_root# name# _value#]
                   (swap! calls# conj [:started name#]))}
                (fn []
                  ((var-get (operator-var# (symbol "start!")))
                   ~(str root) ["cold-start"])))
              (prn @calls#))))
        process
        (.start
         (doto
          (ProcessBuilder.
           ^java.util.List
           ["bb"
            "--config" (str (io/file project-root "bb.edn"))
            "--deps-root" (str project-root)
            "--classpath" operator-classpath
            "-e" code])
           (.directory project-root)
           (.redirectErrorStream true)))
        completed? (.waitFor process 10 TimeUnit/SECONDS)
        _ (when-not completed? (.destroyForcibly process))
        output (str/trim (slurp (.getInputStream process)))]
    (when-not (and completed? (zero? (.exitValue process)))
      (throw
       (ex-info "The cold-start operator probe failed."
                {:seon.dev.fresh-operator-test/output output})))
    (edn/read-string output)))

(defn- launch-observation
  [root]
  (let [code
        (pr-str
         `(do
            (require 'clojure.string 'seon.fresh-operator)
            (let [operator-var#
                  (fn [name#]
                    (ns-resolve 'seon.fresh-operator name#))
                  detach-python#
                  ~(str "import os,sys\n"
                        "open(sys.argv[2],'w').write(os.getcwd()+'\\n'+sys.argv[1]+'\\n'+'\\n'.join(sys.argv[5:]))\n"
                        "print(os.getpid(),flush=True)\n")]
              (with-redefs-fn
                {(operator-var# (symbol "detach-python")) detach-python#}
                (fn []
                  ((var-get (operator-var# (symbol "launch!")))
                   ~(str root) "launch-observation" {} 1 2 30000
                   ~(.getCanonicalPath
                     (io/file project-root
                              "target/dev-dependency-classes/test")))
                  (prn
                   (clojure.string/split-lines
                    (slurp
                     ~(str (io/file root "data" "clusters"
                                    "launch-observation" "logs" "seon.log"))))))))))
        process
        (.start
         (doto
          (ProcessBuilder.
           ^java.util.List
           ["bb"
            "--config" (str (io/file project-root "bb.edn"))
            "--deps-root" (str project-root)
            "--classpath" operator-classpath
            "-e" code])
          (.directory root)
          (.redirectErrorStream true)))
        completed? (.waitFor process 10 TimeUnit/SECONDS)
        _ (when-not completed? (.destroyForcibly process))
        output (str/trim (slurp (.getInputStream process)))]
    (when-not (and completed? (zero? (.exitValue process)))
      (throw
       (ex-info "The launch observation failed."
                {:seon.dev.fresh-operator-test/output output})))
    (edn/read-string output)))

(defn- readiness-simulation
  [root mode]
  (let [code
        (pr-str
         `(do
            (require 'clojure.java.io 'seon.fresh-operator)
            (let [root# ~(str root)
                  name# "simulated-boot"
                  child#
                  (.start
                   (ProcessBuilder.
                    ^java.util.List
                    ["/usr/bin/python3" "-c"
                     "import signal; signal.pause()"]))
                  server#
                  (java.net.ServerSocket.
                   0 1 (java.net.InetAddress/getLoopbackAddress))
                  writer#
                  (Thread.
                   (fn []
                     (with-open
                      [socket#
                       (java.net.Socket.
                        "127.0.0.1" (.getLocalPort server#))
                       output#
                       (clojure.java.io/writer
                        (.getOutputStream socket#))]
                       (.write output# "namespaces\n")
                       (.flush output#)
                       (if (= ~mode :slow)
                         (do
                           (Thread/sleep 150)
                           (.write output# "schema\n")
                           (.flush output#)
                           (Thread/sleep 150)
                           (.write output# "flow\n")
                           (.flush output#)
                           (Thread/sleep 150)
                           (let [directory#
                                 (java.io.File.
                                  root#
                                  (str "data/clusters/" name#))
                                 start#
                                 (.get
                                  (.startInstant
                                   (.info (.toHandle child#))))]
                             (.mkdirs directory#)
                             (spit
                              (java.io.File. directory# "prepl.edn")
                              (pr-str
                               {:seon.boot/cluster-name name#
                                :seon.boot/pid (.pid child#)
                                :seon.boot/start-instant
                                (java.util.Date/from start#)
                                :seon.boot/prepl-port 1
                                :seon.render.web/url
                                "http://127.0.0.1:1"})))
                           (.write output# "ready\n")
                           (.flush output#))
                         (Thread/sleep 1000)))))
                  _# (.setDaemon writer# true)
                  _# (.start writer#)
                  started# (System/nanoTime)
                  outcome#
                  (try
                    {:seon.dev.fresh-operator-test/value
                     (select-keys
                      ((var-get
                        (ns-resolve
                         'seon.fresh-operator
                         (symbol "await-advertisement!")))
                       root# name# (.pid child#) server# 250)
                      [:seon.boot/cluster-name :seon.boot/pid])}
                    (catch Throwable failure#
                      {:seon.dev.fresh-operator-test/message
                       (ex-message failure#)
                       :seon.dev.fresh-operator-test/data
                       (ex-data failure#)}))
                  elapsed-ms#
                  (long (/ (- (System/nanoTime) started#) 1000000))]
              (.close server#)
              (.destroyForcibly child#)
              (.get (.onExit (.toHandle child#)) 5
                    java.util.concurrent.TimeUnit/SECONDS)
              (prn (assoc outcome#
                          :seon.dev.fresh-operator-test/elapsed-ms
                          elapsed-ms#)))))
        process
        (.start
         (doto
          (ProcessBuilder.
           ^java.util.List
           ["bb"
            "--config" (str (io/file project-root "bb.edn"))
            "--deps-root" (str project-root)
            "--classpath" operator-classpath
            "-e" code])
          (.directory project-root)
          (.redirectErrorStream true)))
        completed? (.waitFor process 10 TimeUnit/SECONDS)
        _ (when-not completed? (.destroyForcibly process))
        output (str/trim (slurp (.getInputStream process)))]
    (when-not (and completed? (zero? (.exitValue process)))
      (throw
       (ex-info "The readiness simulation failed."
                {:seon.dev.fresh-operator-test/output output})))
    {:seon.dev.fresh-operator-test/output output
     :seon.dev.fresh-operator-test/outcome
     (edn/read-string (last (str/split-lines output)))}))

(defn- process-output
  [^Process process]
  (future
    (try
      (slurp (.getInputStream process))
      (catch java.io.IOException error
        (str "The child output stream closed after termination: "
             (ex-message error))))))

(defn- process-tree
  [^Process process]
  (let [root (.toHandle process)]
    (with-open [stream (.descendants root)]
      (conj (vec (iterator-seq (.iterator stream))) root))))

(defn- terminate-process-tree!
  [^Process process]
  (let [handles (process-tree process)]
    (doseq [handle handles
            :when (.isAlive ^java.lang.ProcessHandle handle)]
      (.destroyForcibly ^java.lang.ProcessHandle handle))
    (.get
     (CompletableFuture/allOf
      (into-array
       CompletableFuture
       (map #(.onExit ^java.lang.ProcessHandle %) handles)))
     10 TimeUnit/SECONDS)))

(defn- await-process!
  [^Process process output-future context]
  (try
    (.get (.onExit (.toHandle process)) 90 TimeUnit/SECONDS)
    {:seon.dev.fresh-operator-test/completed? true
     :seon.dev.fresh-operator-test/exit (.exitValue process)
     :seon.dev.fresh-operator-test/output
     (deref output-future 10000
            "The operator output reader did not finish.")}
    (catch TimeoutException _
      (terminate-process-tree! process)
      (throw
       (ex-info (str context " exceeded its last-resort backstop.")
                {:seon.dev.fresh-operator-test/output
                 (deref output-future 10000
                        "The terminated output reader did not finish.")})))))

(deftest legacy-operator-jvm-roles-remain-visible-to-orphan-detection
  (doseq [arguments
          [["clojure.main" "-m" "seon.web.server" "{}"]
           ["clojure.main" "-m" "seon.host" "{}"]
           ["-jar" "/checkout/target/seon-database-server-standalone.jar"]
           ["clojure.main" "-m" "shadow.cljs.devtools.cli" "watch"
            "client"]]]
    (is (true?
         (operator-private-value 'legacy-operator-arguments? arguments))))
  (doseq [arguments
          [["clojure.main" "-e" "(clojure.test/run-tests)"]
           ["clojure.main" "-m" "shadow.cljs.devtools.cli" "compile"
            "client"]]]
    (is (false?
         (operator-private-value 'legacy-operator-arguments? arguments)))))

(deftest init-changed-paths-are-an-explicit-source-publication-mode
  (is (= {:seon.fresh-operator/changed-paths
          ["src/seon/fn.clj" "test/seon/fn_test.clj"]
          :seon.fresh-operator/force? false}
         (operator-private-value
          'parse-init-arguments
          ["--changed" "src/seon/fn.clj" "test/seon/fn_test.clj"])))
  (is (str/includes?
       (:seon.dev.fresh-operator-test/message
        (operator-private-outcome 'parse-init-arguments ["--changed"]))
       "init --changed PATH")))

(deftest down-is-the-unambiguous-all-processes-command
  (is (= {:seon.fresh-operator/force? false}
         (operator-private-value 'parse-down-arguments [])))
  (is (= {:seon.fresh-operator/force? true}
         (operator-private-value 'parse-down-arguments ["--force"])))
  (doseq [arguments [["alpha"] ["--force" "alpha"] ["--unknown"]]]
    (let [outcome
          (operator-private-outcome 'parse-down-arguments arguments)]
      (is (str/includes?
           (:seon.dev.fresh-operator-test/message outcome)
           "down [--force]")
          (pr-str outcome)))))

(deftest child-process-records-round-trip-and-fence-pid-reuse
  (let [root (fresh-root)
        child (start-disposable-process!)
        record (child-process-record root child)
        mismatched
        (update record :seon.dev.process/start-instant
                (fn [value]
                  (str (.plusMillis (java.time.Instant/parse value) 1))))]
    (try
      (is (= record
             (operator-private-value
              'write-process-record! (str root) record)))
      (is (= {:seon.fresh-operator/process-records [record]
              :seon.fresh-operator/process-record-errors []}
             (operator-private-value 'read-process-records (str root))))
      (is (true? (operator-private-value 'record-alive? record)))
      (is (false? (operator-private-value 'record-alive? mismatched))
          "a reused PID is not the recorded process generation")
      (is (= :pid-reused
             (operator-private-value
              'terminate-recorded-process! mismatched))
          "the destructive seam refuses a live PID from another generation")
      (is (= true (.isAlive child))
          "the mismatched PID was never signaled")
      (is (true?
           (operator-private-value
            'clear-process-record! (str root) record)))
      (is (= {:seon.fresh-operator/process-records []
              :seon.fresh-operator/process-record-errors []}
             (operator-private-value 'read-process-records (str root))))
      (finally
        (when (.isAlive child)
          (.destroyForcibly child)
          (.waitFor child 10 TimeUnit/SECONDS))
        (delete-recursively! root)))))

(deftest installation-process-claims-remain-scoped-by-operator-root
  (let [root (fresh-root)
        foreign-root (fresh-root)
        record
        {:seon.dev.process/generation (random-uuid)
         :seon.dev.process/pid 1
         :seon.dev.process/start-instant "2026-08-01T00:00:00Z"
         :seon.dev.process/root (.getCanonicalPath foreign-root)
         :seon.dev.process/log (str (io/file foreign-root "foreign.log"))}]
    (try
      (operator-private-value 'write-process-record! (str root) record)
      (let [read-result
            (operator-private-value 'read-process-records (str root))]
        (is (empty? (:seon.fresh-operator/process-records read-result)))
        (is (empty?
             (:seon.fresh-operator/process-record-errors read-result))
            (pr-str read-result)))
      (is (= [record]
             (:seon.fresh-operator/process-records
              (operator-private-value
               'read-process-records (str foreign-root))))
          "the installation authority exposes the claim only to its root")
      (is (= []
             (operator-private-value
              'require-readable-process-records! (str root))))
      (finally
        (operator-private-value
         'clear-process-record! (str foreign-root) record)
        (delete-recursively! root)
        (delete-recursively! foreign-root)))))

(deftest failed-launch-cleanup-signals-only-the-recorded-generation
  (let [parent (start-disposable-process-tree!)
        child-pid (parse-long (.readLine (io/reader (.getInputStream parent))))]
    (try
      (is (some? child-pid))
      (is (nil?
           (operator-private-value 'terminate-observed-process! (.pid parent))))
      (is (true? (.waitFor parent 10 TimeUnit/SECONDS)))
      (is (true?
           (some-> (java.lang.ProcessHandle/of child-pid)
                   (.orElse nil)
                   .isAlive))
          "an unrecorded descendant was never authorized for signaling")
      (finally
        (when (.isAlive parent)
          (terminate-process-tree! parent))
        (when-let [^java.lang.ProcessHandle child
                   (some-> (java.lang.ProcessHandle/of child-pid)
                           (.orElse nil))]
          (when (.isAlive child)
            (.destroyForcibly child)
            (.get (.onExit child) 10 TimeUnit/SECONDS)))))))

(deftest ^{:seon.test/long "Spawns a TERM-resistant child and awaits the process cleanup backstop."}
  failed-launch-cleanup-escalates-after-bounded-term-grace
  (let [process (start-sigterm-resistant-process!)]
    (try
      (is (nil?
           (operator-private-value
            'terminate-observed-process! (.pid process) 250)))
      (is (true? (.waitFor process 10 TimeUnit/SECONDS)))
      (is (not (.isAlive process))
          "the SIGTERM-resistant generation was forcibly reaped")
      (finally
        (when (.isAlive process)
          (.destroyForcibly process)
          (.waitFor process 10 TimeUnit/SECONDS))))))

(defn- run-operator
  [root & arguments]
  (let [process
        (.start
         (doto (ProcessBuilder.
                ^java.util.List
                (apply operator-command root arguments))
           (.directory project-root)
           (.redirectErrorStream true)))
        output-future (process-output process)]
    (await-process! process output-future "The fresh operator")))

(deftest legacy-process-record-directory-is-not-an-authority
  (let [root (fresh-root)
        generation (random-uuid)
        legacy-file
        (io/file root "data" "clusters" "processes"
                 (str generation ".edn"))
        claim-file (process-claim-file generation)
        record
        {:seon.dev.process/generation generation
         :seon.dev.process/pid 2147483647
         :seon.dev.process/start-instant "2026-08-05T00:00:00Z"
         :seon.dev.process/root (.getCanonicalPath root)
         :seon.dev.process/log (str (io/file root "dead.log"))}]
    (try
      (.mkdirs (.getParentFile legacy-file))
      (spit legacy-file (str (pr-str record) "\n"))
      (is (= {:seon.fresh-operator/process-records []
              :seon.fresh-operator/process-record-errors []}
             (operator-private-value
              'reconcile-process-records! (str root))))
      (is (not (.exists claim-file))
          "reconciliation never reads or relocates the legacy record")
      (finally
        (.delete claim-file)
        (delete-recursively! root)))))

(deftest down-without-a-name-stops-every-recorded-child
  (let [root (fresh-root)
        children [(start-disposable-process!)
                  (start-disposable-process!)]
        records (mapv #(child-process-record root %) children)]
    (try
      (doseq [record records]
        (is (= record
               (operator-private-value
                'write-process-record! (str root) record))))
      (let [outcome (run-operator root "down")]
        (is (::completed? outcome) (::output outcome))
        (is (= 0 (::exit outcome)) (::output outcome))
        (is (not (str/includes? (::output outcome) "ambiguous"))
            (::output outcome))
        (let [census-index
              (str/index-of (::output outcome) "PROCESS RECORD CENSUS")
              action-index (str/index-of (::output outcome) "● JVM pid")]
          (is (some? census-index) (::output outcome))
          (is (some? action-index) (::output outcome))
          (is (< census-index action-index)
              "the complete custody census is printed before any action")
          (is (str/includes?
               (::output outcome)
               (str "root=" (.getCanonicalPath root)))
              (::output outcome)))
        (doseq [child children]
          (is (str/includes? (::output outcome) (str "JVM pid " (.pid child)))
              (::output outcome))
          (is (true? (.waitFor child 10 TimeUnit/SECONDS))
              (str "down left recorded PID " (.pid child) " alive"))))
      (is (= {:seon.fresh-operator/process-records []
              :seon.fresh-operator/process-record-errors []}
             (operator-private-value 'read-process-records (str root))))
      (finally
        (doseq [child children
                :when (.isAlive child)]
          (.destroyForcibly child)
          (.waitFor child 10 TimeUnit/SECONDS))
        (delete-recursively! root)))))

(deftest bin-root-option-selects-an-isolated-operator-root
  (let [root (fresh-root)
        record
        {:seon.dev.process/generation (random-uuid)
         :seon.dev.process/pid 1
         :seon.dev.process/start-instant "2026-08-01T00:00:00Z"
         :seon.dev.process/root (.getCanonicalPath root)
         :seon.dev.process/log (str (io/file root "isolated.log"))}]
    (try
      (operator.state/claim-root!
       (.getCanonicalPath project-root)
       (.getCanonicalPath root)
       false
       nil)
      (operator-private-value 'write-process-record! (str root) record)
      (let [process
            (.start
             (doto
              (ProcessBuilder.
               ^java.util.List
               [(str (io/file project-root "bin" "seon"))
                "--root" (str root) "status"])
              (.directory project-root)
              (.redirectErrorStream true)))
            output-future (process-output process)
            outcome (await-process! process output-future "isolated status")]
        (is (::completed? outcome) (::output outcome))
        (is (= 0 (::exit outcome)) (::output outcome))
        (is (str/includes? (::output outcome) "recorded JVM pid 1")
            (::output outcome))
        (is (.isFile
             (io/file (str (operator.state/control-root project-root))
                      "lifecycle.lock"))
            "the installation authority owns one lifecycle lock"))
      (finally
        (operator-private-value
         'clear-process-record! (str root) record)
        (.delete
         (io/file
          (str (operator.state/root-claim-path project-root root))))
        (delete-recursively! root)))))

(defn- await-child-readiness!
  [^ServerSocket ready-server ^Process child child-output]
  (let [readiness
        (CompletableFuture/supplyAsync
         (reify Supplier
           (get [_]
             (with-open [ready-socket (.accept ready-server)
                         ready-reader (io/reader ready-socket)]
               (loop [phases []]
                 (let [value
                       (.readLine ^java.io.BufferedReader ready-reader)]
                   (cond
                     (nil? value)
                     {:seon.dev.fresh-operator-test/event :closed
                      :seon.dev.fresh-operator-test/phases phases}

                     (= "ready" value)
                     {:seon.dev.fresh-operator-test/event :ready
                      :seon.dev.fresh-operator-test/value value
                      :seon.dev.fresh-operator-test/phases phases}

                     :else
                     (recur (conj phases value)))))))))
        exited
        (.thenApply
         (.onExit (.toHandle child))
         (reify Function
           (apply [_ _]
             {:seon.dev.fresh-operator-test/event :exit})))
        winner
        (try
          (.get
           (CompletableFuture/anyOf
            (into-array CompletableFuture [readiness exited]))
           90 TimeUnit/SECONDS)
          (catch ExecutionException error
            (throw (.getCause error)))
          (catch TimeoutException _
            (terminate-process-tree! child)
            (throw
             (ex-info "The anchor readiness exceeded its last-resort backstop."
                      {:seon.dev.fresh-operator-test/output
                       (deref child-output 10000
                              "The anchor output reader did not finish.")}))))]
    (when (= :exit (:seon.dev.fresh-operator-test/event winner))
      (throw
       (ex-info "The anchor exited before readiness."
                {:seon.dev.fresh-operator-test/output
                 (deref child-output 10000
                        "The anchor output reader did not finish.")})))
    (when-not (= "ready" (:seon.dev.fresh-operator-test/value winner))
      (throw (ex-info "The anchor returned malformed readiness."
                      {:seon.dev.fresh-operator-test/value winner})))
    winner))

(defn- prepl-eval
  [advertisement form]
  (with-open [socket (java.net.Socket.)]
    (.connect socket
              (java.net.InetSocketAddress.
               ^String (:seon.boot/prepl-host advertisement)
               (int (:seon.boot/prepl-port advertisement)))
              10000)
    (.setSoTimeout socket 10000)
    (with-open [writer (io/writer socket)
                reader (java.io.PushbackReader. (io/reader socket))]
      (.write writer form)
      (.write writer "\n")
      (.flush writer)
      (loop []
        (let [event (edn/read {:eof ::eof} reader)]
          (cond
            (= ::eof event)
            (throw (ex-info "The child prepl closed before returning." {}))

            (= :ret (:tag event))
            (if (:exception event)
              (throw
               (ex-info "The child prepl rejected the operation."
                        {:seon.dev.fresh-operator-test/event event}))
              (:val event))

            :else
            (recur)))))))

(defn- registry-without-render-value-form
  []
  (pr-str
   '(let [state (seon.schema/snapshot-state)]
      (seon.schema/restore-state!
       (-> state
           (update :seon.schema.state/candidate-forms
                   dissoc
                   :seon.render/value)
           (assoc :seon.schema.state/projection nil)))
      :schema-stale)))

(defn- fresh-process-operator-paths
  [root]
  (with-open [ready-server
              (ServerSocket.
               0 1 (java.net.InetAddress/getLoopbackAddress))]
    (let [launch-form
          (operator-private-value
           'launch-form (str root) "anchor" {} (.getLocalPort ready-server))
          code
          (pr-str
           `(do
              (require 'seon.cluster
                       'seon.instrument
                       'seon.render.value
                       'seon.schema)
              (let [state# (seon.schema/snapshot-state)
                    original-resolve# seon.cluster/resolve-bootstrap]
                (seon.schema/restore-state!
                 (-> state#
                     (update :seon.schema.state/candidate-forms
                             dissoc
                             :seon.render/value)
                     (assoc :seon.schema.state/projection nil)))
                (with-redefs
                  [seon.cluster/resolve-bootstrap
                  (fn [overrides#]
                    (original-resolve#
                     (assoc overrides#
                            :seon.boot/root
                            ~(str (io/file root "data" "clusters")))))]
                  (eval (read-string ~launch-form))))))
          child
          (.start
           (doto
            (ProcessBuilder.
             ^java.util.List
             ["clojure" "-M:test" "-e" code])
             (.directory project-root)
             (.redirectErrorStream true)))
          child-output (process-output child)]
      (try
        (let [readiness
              (await-child-readiness! ready-server child child-output)
              anchor-advertisement
              (edn/read-string
               (slurp (io/file root "data" "clusters"
                               "anchor" "prepl.edn")))
              _ (prepl-eval anchor-advertisement
                            (registry-without-render-value-form))
              added (run-operator root "start" "scratch")
              scratch-advertisement
              (when (zero? (or (::exit added) 1))
                (edn/read-string
                 (slurp (io/file root "data" "clusters"
                                 "scratch" "prepl.edn"))))]
          {::anchor-ready? true
           ::anchor-phases
           (:seon.dev.fresh-operator-test/phases readiness)
           ::add-completed? (::completed? added)
           ::add-exit (::exit added)
           ::add-output (::output added)
           ::scratch-ready?
           (boolean (:seon.render.web/url scratch-advertisement))})
        (finally
          (when (.isAlive child)
            (run-operator root "stop" "scratch")
            (run-operator root "stop" "anchor"))
          (when (.isAlive child)
            (terminate-process-tree! child))
          (deref child-output 10000
                 "The anchor output reader did not finish."))))))

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

(deftest destructive-stop-selection-requires-one-unambiguous-cluster
  (let [row
        (fn [name]
          {:seon.fresh-operator/name name
           :seon.fresh-operator/operator-root? true
           :seon.fresh-operator/persisted? true})]
    (is (= "only"
           (operator-private-value
            'select-destructive-name [(row "only")] nil "stop")))
    (is (= "zombie"
           (operator-private-value
            'select-destructive-name
            [{:seon.fresh-operator/name "zombie"
              :seon.fresh-operator/operator-root? true
              :seon.fresh-operator/advertised? true
              :seon.fresh-operator/process-alive? true}]
            nil
            "stop"))
        "a sole advertised zombie remains the unambiguous stop target")
    (let [outcome
          (operator-private-outcome
           'select-destructive-name
           [(row "alpha") (row "beta")]
           nil
           "stop")]
      (is (str/includes?
           (:seon.dev.fresh-operator-test/message outcome)
           "Refusing ambiguous `stop` because it destroys cluster data"))
      (is (= ["alpha" "beta"]
             (get-in outcome
                     [:seon.dev.fresh-operator-test/data
                      :seon.fresh-operator/candidates]))))))

(deftest cold-start-defers-roster-read-to-the-launched-jvm
  (let [root (fresh-root)]
    (try
      (is (= [:dependency-cache
              [:launch "cold-start"]
              [:started "cold-start"]]
             (cold-start-calls root))
          "cold start prepared its cache and launched without an offline roster JVM")
      (finally
        (delete-recursively! root)))))

(deftest isolated-root-launch-keeps-repository-classpath-and-root-property
  (let [root (fresh-root)]
    (try
      (let [[launcher-directory child-directory & child-command]
            (launch-observation root)]
        (is (= (.getCanonicalPath project-root) launcher-directory))
        (is (= (.getCanonicalPath project-root) child-directory))
        (is (some #{(str "-J-Dseon.operator.root="
                         (.getCanonicalPath root))}
                  child-command))
        (is (some #{"-M:dev:seon-cache"} child-command))
        (is (some #(str/starts-with?
                    % "-J-Dseon.dependency-cache.path=")
                  child-command))
        (is (str/includes? (last child-command)
                           (str (io/file root "data" "clusters")))))
      (finally
        (.delete
         (io/file
          (str (operator.state/root-claim-path project-root root))))
        (delete-recursively! root)))))

(deftest boot-readiness-resets-the-silence-backstop-on-every-phase
  (let [root (fresh-root)]
    (try
      (let [{:seon.dev.fresh-operator-test/keys [outcome]}
            (readiness-simulation root :slow)]
        (is (= "simulated-boot"
               (get-in outcome
                       [:seon.dev.fresh-operator-test/value
                        :seon.boot/cluster-name])))
        (is (<= 400
                (:seon.dev.fresh-operator-test/elapsed-ms outcome))
            "total boot time exceeded one silence interval without failure"))
      (finally
        (delete-recursively! root)))))

(deftest boot-readiness-names-the-phase-that-goes-silent
  (let [root (fresh-root)]
    (try
      (let [{:seon.dev.fresh-operator-test/keys [output outcome]}
            (readiness-simulation root :silent)]
        (is (= :seon.fresh-operator/boot-phase-silent
               (get-in outcome
                       [:seon.dev.fresh-operator-test/data
                        :seon.error/kind])))
        (is (= "namespaces"
               (get-in outcome
                       [:seon.dev.fresh-operator-test/data
                        :seon.fresh-operator/phase])))
        (is (= 250
               (get-in outcome
                       [:seon.dev.fresh-operator-test/data
                        :seon.fresh-operator/silence-backstop-ms])))
        (is (str/includes?
             output
             (str "operator event silence backstop fired: cluster boot phase "
                  "namespaces was silent for 250 ms"))))
      (finally
        (delete-recursively! root)))))

(deftest every-child-jvm-command-uses-the-shared-launch-owner
  (let [source (slurp (io/file project-root "script" "seon"
                               "fresh_operator.clj"))]
    (is (= 2 (count (re-seq #"\[\"clojure\"" source)))
        "only the child owner and dependency-cache target construct Clojure commands")
    (is (= 4 (count (re-seq #"\(start-child-jvm!" source)))
        (str "offline status, cluster start, initialization, and managed-root "
             "cleanup use one owner"))))

(deftest ^{:seon.test/long "Runs complete source initialization through a fresh operator JVM."}
  init-owns-current-source-and-dormant-cluster-lifecycle
  (let [root (fresh-root)
        store-dir (str (io/file root "data" "clusters" "store"))
        name "init-command"
        current-digest
        (:seon.source/digest (cluster/source-snapshot))]
    (try
      (let [bare (run-operator root "init")]
        (is (::completed? bare) (::output bare))
        (is (= 0 (::exit bare)) (::output bare))
        (is (str/includes? (::output bare) (str source/current-branch))
            (::output bare))
        (is (str/includes? (::output bare) current-digest)
            (::output bare)))
      (let [status (run-operator root "status")]
        (is (= 0 (::exit status)) (::output status))
        (is (str/includes? (::output status) "0/0 clusters alive")
            (::output status))
        (is (not (str/includes? (::output status) "roster unreadable"))
            (::output status)))
      (let [created (run-operator root "init" name)
            refused (run-operator root "init" name)
            reforked (run-operator root "init" name "--force")]
        (is (= 0 (::exit created)) (::output created))
        (is (str/includes? (::output created)
                           (str "● " name " forked :cluster-" name))
            (::output created))
        (is (= 1 (::exit refused)) (::output refused))
        (is (str/includes? (::output refused) "already exists")
            (::output refused))
        (is (= 0 (::exit reforked)) (::output reforked))
        (is (str/includes? (::output reforked)
                           (str "● " name " reforked :cluster-" name))
            (::output reforked)))
      (let [opened (store/open-store! {:seon.store/dir store-dir})]
        (try
          (let [roster (registry/roster opened)]
            (is (contains? roster source/current-branch))
            (is (contains? roster (registry/cluster-branch name)))
            (is (not (contains? roster :cluster-default))
                "bare init does not invent a default cluster"))
          (finally
            (store/release-store! opened))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Runs live initialization and reload through a fresh operator JVM."}
  live-init-reloads-a-moved-core-predicate-owner-before-admission
  (let [root (fresh-root)
        cluster-name "predicate-owner-reload"
        launched-identities (atom [])]
    (try
      (let [published (run-operator root "init")
            forked (run-operator root "init" cluster-name)
            started (run-operator root "start" cluster-name)]
        (is (= 0 (::exit published)) (::output published))
        (is (= 0 (::exit forked)) (::output forked))
        (is (= 0 (::exit started)) (::output started)))
      (swap! launched-identities conj
             (advertisement-process-identity root cluster-name))
      (let [advertisement
            (edn/read-string
             (slurp (io/file root "data" "clusters"
                             cluster-name "prepl.edn")))
            stale
            (edn/read-string
             (prepl-eval
              advertisement
              (pr-str
               `(do
                  (require 'seon.db 'seon.schema)
                  (seon.schema/restore-state!
                   (update-in
                    (seon.schema/snapshot-state)
                    [:seon.schema.state/predicate-functions]
                    dissoc
                    'seon.db/connection?
                    'seon.db/database-value?))
                  {:seon.dev.fresh-operator-test/owner-loaded?
                   (boolean (find-ns 'seon.db))
                   :seon.dev.fresh-operator-test/connection-registered?
                   (seon.schema/core-predicate-registered?
                    'seon.db/connection?)}))))]
        (is (true? (::owner-loaded? stale)) stale)
        (is (false? (::connection-registered? stale)) stale))
      (let [advertisement
            (edn/read-string
             (slurp (io/file root "data" "clusters"
                             cluster-name "prepl.edn")))
            stale
            (edn/read-string
             (prepl-eval
              advertisement
              (pr-str
               `(do
                  (require 'seon.program)
                  (alter-var-root
                   #'seon.program/canonical-row
                   (constantly
                    (fn [row#]
                      (dissoc row# :seon.fn/calls))))
                  {:seon.dev.fresh-operator-test/program-stale? true}))))]
        (is (true? (::program-stale? stale)) stale))
      (let [republished (run-operator root "init")]
        (is (= 0 (::exit republished)) (::output republished))
        (is (str/includes? (::output republished) (str source/current-branch))
            (::output republished)))
      (finally
        (try
          (run-operator root "down")
          (catch Throwable failure
            (binding [*out* *err*]
              (println "operator down cleanup failed:" (ex-message failure)))))
        (doseq [process-identity (distinct @launched-identities)]
          (reap-process-identity! process-identity))
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Destructively resets and republishes a source-less operator root."}
  source-less-root-reset-republishes-and-reforks-default
  (let [root (fresh-root)
        store-dir (str (io/file root "data" "clusters" "store"))]
    (try
      (let [reset (run-operator root "reset" "--force")]
        (is (= 0 (::exit reset)) (::output reset))
        (is (str/includes? (::output reset)
                           "reset republished current-src and reforked default")
            (::output reset)))
      (let [opened (store/open-store! {:seon.store/dir store-dir})]
        (try
          (let [roster (registry/roster opened)]
            (is (contains? roster source/current-branch))
            (is (contains? roster :cluster-default)))
          (finally
            (store/release-store! opened))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Boots a real JVM, SIGKILLs it, then proves forced reset self-recovers."}
  forced-reset-clears-an-exact-dead-process-record
  (let [root (fresh-root)
        record* (atom nil)]
    (try
      (let [published (run-operator root "init")]
        (is (= 0 (::exit published)) (::output published)))
      (let [started (run-operator root "start" "dead-reset")]
        (is (= 0 (::exit started)) (::output started)))
      (let [record
            (first
             (:seon.fresh-operator/process-records
              (operator-private-value 'read-process-records (str root))))
            _ (reset! record* record)
            generation (:seon.dev.process/generation record)
            legacy-file
            (io/file root "data" "clusters" "processes"
                     (str generation ".edn"))
            handle
            (some-> (java.lang.ProcessHandle/of
                     (long (:seon.dev.process/pid record)))
                    (.orElse nil))]
        (is (map? record) "the booted JVM published its exact claim")
        (is (some? handle) "the recorded JVM still has a process handle")
        (.mkdirs (.getParentFile legacy-file))
        (spit legacy-file (str (pr-str record) "\n"))
        (.destroyForcibly ^java.lang.ProcessHandle handle)
        (.get (.onExit ^java.lang.ProcessHandle handle)
              10 TimeUnit/SECONDS)
        (is (false? (operator-private-value 'record-alive? record)))
        (let [reset (run-operator root "reset" "--force")]
          (is (= 0 (::exit reset)) (::output reset))
          (is (str/includes? (::output reset) "path=already-exited")
              (::output reset))
          (is (str/includes?
               (::output reset)
               "reset republished current-src and reforked default")
              (::output reset)))
        (is (not (.exists (process-claim-file generation)))
            "forced reset removed the confirmed-dead exact claim")
        (is (not (.exists legacy-file))
            "reset wiped the ignored legacy managed-tree residue"))
      (finally
        (when-let [record @record*]
          (when (operator-private-value 'record-alive? record)
            (operator-private-value 'terminate-recorded-process! record))
          (operator-private-value
           'clear-process-record! (str root) record))
        (delete-recursively! root)))))

(defn- live-cluster-counts
  [root name marker]
  (let [advertisement
        (edn/read-string
         (slurp (io/file root "data" "clusters" name "prepl.edn")))
        form
        (pr-str
         `(do
            (require 'datahike.api 'seon.cluster)
            (let [instance#
                  (get @@(ns-resolve 'seon.cluster
                                     (symbol "running-instances"))
                       ~name)
                  connection# (:seon.boot/cluster-connection instance#)]
              {:seon.dev.fresh-operator-test/marker-count
               (datahike.api/q
                '[:find (count ?e) .
                  :in $ ?marker
                  :where [?e :seon.db.process/id ?marker]]
                @connection# ~marker)
               :seon.dev.fresh-operator-test/agent-count
               (datahike.api/q
                '[:find (count ?e) .
                  :where [?e :seon.cluster.agent/id]]
                @connection#)})))]
    (edn/read-string (prepl-eval advertisement form))))

(defn- populate-live-cluster!
  [root name marker]
  (let [advertisement
        (edn/read-string
         (slurp (io/file root "data" "clusters" name "prepl.edn")))
        form
        (pr-str
         `(do
            (require 'datahike.api 'seon.cluster)
            (let [instance#
                  (get @@(ns-resolve 'seon.cluster
                                     (symbol "running-instances"))
                       ~name)
                  connection# (:seon.boot/cluster-connection instance#)]
              (datahike.api/transact
               connection# [{:seon.db.process/id ~marker}])
              :populated)))]
    (edn/read-string (prepl-eval advertisement form))))

(deftest ^{:seon.test/long "Restarts a populated cluster across two fresh operator JVMs."}
  populated-stopped-cluster-reopens-after-full-operator-restart
  (let [root (fresh-root)
        name "restart-populated"
        marker "restart-populated-marker"
        launched-identities (atom [])]
    (try
      (let [published (run-operator root "init")
            forked (run-operator root "init" name)]
        (is (= 0 (::exit published)) (::output published))
        (is (= 0 (::exit forked)) (::output forked)))
      (let [started (run-operator root "start" name)]
        (is (= 0 (::exit started)) (::output started))
        (is (str/includes? (::output started) name) (::output started)))
      (swap! launched-identities conj
             (advertisement-process-identity root name))
      (is (= :populated (populate-live-cluster! root name marker)))
      (let [before (live-cluster-counts root name marker)
            live-status (run-operator root "status")]
        (is (= 1 (::marker-count before)))
        (is (= 0 (::exit live-status)) (::output live-status))
        (is (str/includes? (::output live-status) "1/1 clusters alive")
            (::output live-status))
        (let [stopped (run-operator root "stop" name)]
          (is (= 0 (::exit stopped)) (::output stopped)))
      (let [stopped-status (run-operator root "status")]
        (is (= 0 (::exit stopped-status)) (::output stopped-status))
        (is (str/includes? (::output stopped-status) name)
            (::output stopped-status))
        (is (str/includes? (::output stopped-status) "stopped")
            (::output stopped-status))
        (is (str/includes? (::output stopped-status) "0/0 clusters alive")
            (::output stopped-status)))
      (let [started (run-operator root "start" name)]
        (is (= 0 (::exit started)) (::output started))
        (is (str/includes? (::output started) name) (::output started)))
      (swap! launched-identities conj
             (advertisement-process-identity root name))
      (is (= 2 (count @launched-identities)))
      (is (not= (first @launched-identities)
                (second @launched-identities))
          "the reopen crossed a new `(pid, start-instant)` JVM identity")
      (let [after (live-cluster-counts root name marker)
            live-status (run-operator root "status")]
        (is (= before after)
            "the new operator JVM reopened every observed branch fact")
        (is (= 0 (::exit live-status)) (::output live-status))
        (is (str/includes? (::output live-status) "1/1 clusters alive")
            (::output live-status))))
      (let [stopped (run-operator root "stop" name)]
        (is (= 0 (::exit stopped)) (::output stopped)))
      (finally
        (try
          (when (.isFile (io/file root "data" "clusters" name "prepl.edn"))
            (swap! launched-identities conj
                   (advertisement-process-identity root name)))
          (catch Throwable _))
        (try
          (run-operator root "down")
          (catch Throwable failure
            (binding [*out* *err*]
              (println "operator down cleanup failed:" (ex-message failure)))))
        (doseq [process-identity (distinct @launched-identities)]
          (reap-process-identity! process-identity))
        (delete-recursively! root)))))

(deftest add-refreshes-a-genuinely-stale-wrapper-before-current-start
  (let [root (fresh-root)
        form (operator-private-value 'add-form (str root) "scratch" {})
        start-var #'cluster/start!
        start-meta (meta start-var)
        instances-var
        (ns-resolve 'seon.cluster (symbol "running-instances"))
        instances-before @(var-get instances-var)
        connection (atom nil)
        current-request
        {:seon.boot/root (str (io/file root "data" "clusters"))
         :seon.boot/cluster-name "scratch"
         :seon.config/manifest {}}
        stale-schema
        [:=> [:cat
              [:map
               [:seon.boot/root :string]
               [:seon.boot/cluster-name :string]
               [:seon.config/manifest :string]]]
         :map]
        current-schema
        [:=> [:cat
              [:map
               [:seon.boot/root :string]
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
            "the installed wrapper still enforces the old value shape")
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

(deftest ^{:seon.test/long "Loads and instruments schemas in a genuinely fresh operator process."}
  fresh-process-loads-schema-before-every-operator-instrumentation
  (let [root (fresh-root)]
    (try
      (let [initialized (run-operator root "init")]
        (is (= 0 (::exit initialized)) (::output initialized))
        (let [{::keys [anchor-ready? anchor-phases add-completed? add-exit
                       add-output scratch-ready?]}
              (fresh-process-operator-paths root)]
          (is anchor-ready?
              "the generated launch form instrumented before publishing ready")
          (is (= ["namespaces" "repl" "store" "branch" "recovery"
                  "config" "program" "work-launcher" "agents" "web"
                  "ready"]
                 anchor-phases)
              "the readiness socket reports every published tower boundary")
          (is add-completed? "the generated add form completed")
          (is (= 0 add-exit) add-output)
          (is scratch-ready?
              "the added scratch cluster published its web URL")))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Carries boot refusal and readiness over the cached phase protocol."}
  isolated-cached-boot-reports-refusal-then-reaches-readiness
  (let [root (fresh-root)
        name "cached-readiness"
        launched-identities (atom [])]
    (try
      (let [refused (run-operator root "start" name)]
        (is (= 1 (::exit refused)) (::output refused))
        (is (str/includes?
             (::output refused)
             "No `current-src` branch is published; run `bin/seon init` first.")
            (::output refused))
        (is (not (str/includes? (::output refused) "malformed readiness"))
            (::output refused))
        (is (str/includes? (::output refused)
                           (str "● " name " boot: store"))
            "phase progress remains visible before the terminal refusal"))
      (let [initialized (run-operator root "init")
            started (run-operator root "start" name)]
        (is (= 0 (::exit initialized)) (::output initialized))
        (is (= 0 (::exit started)) (::output started))
        (is (str/includes? (::output started) "#:seon.dev-cache{")
            "the isolated launch selected the dependency cache")
        (doseq [phase ["namespaces" "repl" "store" "branch" "recovery"
                       "config" "program" "work-launcher" "agents" "web"]]
          (is (str/includes? (::output started)
                             (str "● " name " boot: " phase))
              (str "missing readiness phase " phase " from "
                   (::output started))))
        (let [advertisement
              (edn/read-string
               (slurp (io/file root "data" "clusters" name "prepl.edn")))]
          (is (= name (:seon.boot/cluster-name advertisement)))
          (is (string? (:seon.render.web/url advertisement))))
        (swap! launched-identities conj
               (advertisement-process-identity root name)))
      (finally
        (try
          (when (.isFile (io/file root "data" "clusters" name "prepl.edn"))
            (swap! launched-identities conj
                   (advertisement-process-identity root name)))
          (catch Throwable _))
        (try
          (run-operator root "down")
          (catch Throwable _))
        (doseq [process-identity (distinct @launched-identities)]
          (reap-process-identity! process-identity))
        (delete-recursively! root)))
    (is (not (.exists root))
        "the cached-boot fixture root was deleted after its exact JVM exited")))

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
          (loop []
            (let [form
                  (with-open [socket (.accept server)
                              reader (io/reader socket)
                              writer (io/writer socket)]
                    (let [form (.readLine ^java.io.BufferedReader reader)]
                      (.write writer
                              (str (pr-str {:tag :ret
                                            :val "nil"
                                            :exception true})
                                   "\n"))
                      (.flush writer)
                      form))]
              (if (str/includes? form "seon.cluster/stop!")
                (deliver received form)
                (recur)))))]
    (try
      (let [directory (io/file root "data" "clusters" name)
            record (child-process-record root child)
            advertisement
            {:seon.boot/cluster-name name
             :seon.boot/pid (.pid child)
             :seon.boot/start-instant (process-start-date child)
             :seon.boot/prepl-host "127.0.0.1"
             :seon.boot/prepl-port (.getLocalPort server)}
            _ (.mkdirs directory)
            _ (operator-private-value
               'write-process-record! (str root) record)
            _ (spit (io/file directory "prepl.edn")
                    (pr-str advertisement))
            process
            (.start
             (doto (ProcessBuilder.
                    ^java.util.List (operator-command root "stop" name))
               (.directory project-root)
               (.redirectErrorStream true)))
            output-future (process-output process)
            completed? (.waitFor process 20 TimeUnit/SECONDS)
            _ (when-not completed? (.destroyForcibly process))
            output (deref output-future 10000
                          "The stopped operator output did not close.")
            stop-form (deref received 10000 ::timeout)
            child-stopped? (.waitFor child 10 TimeUnit/SECONDS)]
        (testing "the remote eval exception is a named, loud fallback"
          (is completed? "The operator exceeded twenty seconds.")
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
        (try
          (deref served 1000 nil)
          (catch Throwable _ nil))
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
          (loop []
            (let [form
                  (with-open [socket (.accept server)
                              reader (io/reader socket)
                              writer (io/writer socket)]
                    (let [form (.readLine ^java.io.BufferedReader reader)]
                      (.write writer
                              (str (pr-str {:tag :ret
                                            :val "nil"
                                            :exception true})
                                   "\n"))
                      (.flush writer)
                      form))]
              (if (str/includes? form "seon.cluster/stop!")
                (deliver received form)
                (recur)))))]
    (try
      (let [start-instant (process-start-date child)
            record (child-process-record root child)
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
        (operator-private-value
         'write-process-record! (str root) record)
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
              output-future (process-output process)
              completed? (.waitFor process 20 TimeUnit/SECONDS)
              _ (when-not completed? (.destroyForcibly process))
              output (deref output-future 10000
                            "The refused operator output did not close.")
              stop-form (deref received 10000 ::timeout)]
          (is completed? "the refusal exceeded twenty seconds")
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
        (try
          (deref served 1000 nil)
          (catch Throwable _ nil))
        (when (.isAlive child)
          (.destroyForcibly child)
          (.waitFor child 10 TimeUnit/SECONDS))
        (delete-recursively! root)))))
