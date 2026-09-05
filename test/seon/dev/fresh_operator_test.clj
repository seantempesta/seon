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
            [seon.env :as env]
            [seon.fresh-operator]
            [seon.instrument :as instrument]
            [seon.operator.state :as operator.state]
            [seon.schema :as schema]
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

(defn- process-start-date
  [^Process process]
  (let [optional (.startInstant (.info (.toHandle process)))]
    (when-not (.isPresent optional)
      (throw (ex-info "The test child has no start instant." {})))
    (Date/from (.get optional))))

(defn- child-process-record
  [root ^Process child]
  {:seon.operator.process-record/generation (random-uuid)
   :seon.boot/pid (.pid child)
   :seon.boot/start-instant
   (process-start-date child)
   :seon.operator.process-record/root (.getCanonicalPath (io/file root))
   :seon.operator.process-record/log
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
  (apply (var-get (ns-resolve 'seon.fresh-operator function-name)) arguments))

(defn- operator-private-outcome
  [function-name & arguments]
  (try
    {:seon.dev.fresh-operator-test/value
     (apply operator-private-value function-name arguments)}
    (catch Throwable failure
      {:seon.dev.fresh-operator-test/message (ex-message failure)
       :seon.dev.fresh-operator-test/data (ex-data failure)})))

(deftest current-test-without-result-evidence-is-unknown
  (let [namespace-name 'seon.absence-proof-test
        absent-test 'seon.absence-proof-test/planted-absence
        status
        (first
         (operator-private-value
          'derive-namespace-test-statuses
          {:seon.test.status/current-tests
           [[namespace-name absent-test]]
           :seon.test.status/latest-results []}))]
    (is (= namespace-name (:seon.test.status/namespace status)))
    (is (= :unknown (:seon.test.status/state status)))
    (is (false?
         (:seon.test.status/all-current-tests-last-known-green? status)))
    (is (= [absent-test] (:seon.test.status/absent status)))
    (is (= (str "namespace " namespace-name
                ": UNKNOWN; 1 absent results")
           (operator-private-value
            'namespace-test-status-line (Date.) status false)))
    (is (= (str "namespace " namespace-name
                ": UNKNOWN; absent results: " absent-test)
           (operator-private-value
            'namespace-test-status-line (Date.) status true)))))

(deftest wholly-absent-test-evidence-is-one-bounded-loud-line
  (let [statuses
        [{:seon.test.status/namespace 'alpha-test
          :seon.test.status/state :unknown
          :seon.test.status/oldest-run-at nil
          :seon.test.status/absent ['alpha-test/a 'alpha-test/b]
          :seon.test.status/red []}
         {:seon.test.status/namespace 'beta-test
          :seon.test.status/state :unknown
          :seon.test.status/oldest-run-at nil
          :seon.test.status/absent ['beta-test/a]
          :seon.test.status/red []}]]
    (is (= (str "test evidence: UNKNOWN for 2 namespaces "
                "(3 current tests have no recorded results); run bin/test; "
                "details: bin/seon status --verbose")
           (operator-private-value 'absent-test-evidence-line statuses)))))

(deftest status-detail-is-an-explicit-flag
  (is (false? (operator-private-value 'parse-status-arguments [])))
  (is (true? (operator-private-value
              'parse-status-arguments ["--verbose"])))
  (is (= "Use `status [--verbose]`."
         (:seon.dev.fresh-operator-test/message
          (operator-private-outcome
           'parse-status-arguments ["--details"])))))

(deftest reachable-prepl-without-roster-evidence-is-not-called-unreachable
  (let [root (.getCanonicalPath (fresh-root))
        source-observations-var
        (ns-resolve 'seon.fresh-operator 'source-observations)]
    (try
      (with-redefs-fn
        {source-observations-var
         (fn [_]
           {:seon.fresh-operator/advertisements []
            :seon.fresh-operator/jvms
            [{:seon.fresh-operator/root root
              :seon.fresh-operator/reachable? true
              :seon.fresh-operator/persisted-branches-observed? false}]
            :seon.fresh-operator/process-records []
            :seon.fresh-operator/process-record-errors []})}
        (fn []
          (let [truth (operator-private-value 'cluster-truth root)
                roster (:seon.fresh-operator/roster (meta truth))]
            (is (= :live-jvm (:seon.fresh-operator/roster-source roster)))
            (is (= :seon.error/unknown
                   (:seon.error/diagnostic-evidence roster)))
            (is (not (str/includes?
                      (:seon.fresh-operator/roster-error roster)
                      "prepl is unreachable"))))))
      (finally
        (delete-recursively! root)))))

(defn- cold-start-calls
  [root]
  (let [calls (atom [])
        start-instant (Date. 1000)
        generation (random-uuid)
        operator-var #(ns-resolve 'seon.fresh-operator %)
        advertisement
        {:seon.boot/cluster-name "cold-start"
         :seon.boot/pid 42
         :seon.boot/start-instant start-instant
         :seon.render.web/url "http://127.0.0.1:7994"
         :seon.boot/prepl-port 7993}]
    (with-redefs-fn
      {(operator-var 'source-observations)
       (constantly {:seon.fresh-operator/advertisements []
                    :seon.fresh-operator/jvms []})
       (operator-var 'offline-roster)
       (fn [_]
         (swap! calls conj :offline-roster)
         (throw (ex-info "cold start read the offline roster" {})))
       (operator-var 'ensure-dependency-cache!)
       (fn []
         (swap! calls conj :dependency-cache)
         {:seon.dev-cache/path
          (.getCanonicalPath
           (io/file project-root "target/dev-dependency-classes/test"))})
       (operator-var 'launch!)
       (fn [_ name _ _ _ _ _]
         (swap! calls conj [:launch name])
         {:seon.fresh-operator/pid 42})
       (operator-var 'record-launched-process!)
       (fn [_ _ _ _]
         {:seon.operator.process-record/generation generation
          :seon.boot/pid 42
          :seon.boot/start-instant start-instant
          :seon.operator.process-record/root (str root)
          :seon.operator.process-record/log "test.log"})
       (operator-var 'await-advertisement!) (fn [& _] advertisement)
       (operator-var 'print-started!)
       (fn [_ name _] (swap! calls conj [:started name]))}
      #(operator-private-value 'start! (str root) ["cold-start"]))
    @calls))

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
  (let [root (str root)
        name "simulated-boot"
        child (start-disposable-process!)
        server (ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))
        writer
        (Thread.
         (fn []
           (with-open [socket (java.net.Socket. "127.0.0.1"
                                                (.getLocalPort server))
                       output (io/writer (.getOutputStream socket))]
             (doseq [phase (if (= mode :slow)
                             ["namespaces" "schema" "flow"]
                             ["namespaces"])]
               (.write output (str phase "\n"))
               (.flush output)
               (Thread/sleep (if (= mode :slow) 150 1000)))
             (when (= mode :slow)
               (let [directory (io/file root "data" "clusters" name)]
                 (.mkdirs directory)
                 (spit (io/file directory "prepl.edn")
                       (pr-str {:seon.boot/cluster-name name
                                :seon.boot/pid (.pid child)
                                :seon.boot/start-instant
                                (process-start-date child)
                                :seon.boot/prepl-port 1
                                :seon.render.web/url "http://127.0.0.1:1"})))
               (.write output "ready\n")
               (.flush output)))))
        captured (java.io.StringWriter.)]
    (.setDaemon writer true)
    (.start writer)
    (try
      (let [started (System/nanoTime)
            outcome
            (binding [*out* captured]
              (try
                {:seon.dev.fresh-operator-test/value
                 (select-keys
                  (operator-private-value 'await-advertisement!
                                          root name (.pid child) server 250)
                  [:seon.boot/cluster-name :seon.boot/pid])}
                (catch Throwable failure
                  {:seon.dev.fresh-operator-test/message (ex-message failure)
                   :seon.dev.fresh-operator-test/data (ex-data failure)})))]
        {:seon.dev.fresh-operator-test/output (str captured)
         :seon.dev.fresh-operator-test/outcome
         (assoc outcome :seon.dev.fresh-operator-test/elapsed-ms
                (quot (- (System/nanoTime) started) 1000000))})
      (finally
        (.close server)
        (.destroyForcibly child)
        (.get (.onExit (.toHandle child)) 5 TimeUnit/SECONDS)))))

(defn- prepl-response-simulation
  [mode]
  (let [server (ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))
        served
        (future
          (with-open [socket (.accept server)
                      reader (io/reader socket)
                      writer (io/writer socket)]
            (.readLine ^java.io.BufferedReader reader)
            (if (= mode :progress)
              (do
                (doseq [value ["analysis\n" "schema population\n"
                               "branch publication\n"]]
                  (Thread/sleep 120)
                  (.write writer (str (pr-str {:tag :out :val value}) "\n"))
                  (.flush writer))
                (.write writer (str (pr-str {:tag :ret :val "{:ok true}"})
                                    "\n"))
                (.flush writer))
              (Thread/sleep 600))))
        observed (atom [])
        captured (java.io.StringWriter.)
        started (System/nanoTime)
        outcome
        (binding [*out* captured]
          (try
            {:seon.dev.fresh-operator-test/value
             (operator-private-value
              'prepl-eval!
              {:seon.boot/prepl-host "127.0.0.1"
               :seon.boot/prepl-port (.getLocalPort server)}
              "(+ 1 2)" 250 #(swap! observed conj %))}
            (catch Throwable failure
              {:seon.dev.fresh-operator-test/message (ex-message failure)
               :seon.dev.fresh-operator-test/data (ex-data failure)})))]
    (.close server)
    (deref served 1000 nil)
    {:seon.dev.fresh-operator-test/output (str captured)
     :seon.dev.fresh-operator-test/outcome
     (assoc outcome
            :seon.dev.fresh-operator-test/observed @observed
            :seon.dev.fresh-operator-test/elapsed-ms
            (quot (- (System/nanoTime) started) 1000000))}))

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
  [^Process process output-future _context]
  ;; Process exit and output EOF are observable completion events. The old
  ;; 90-second wall-clock verdict killed healthy operator commands under the
  ;; nine-worker load, before the same commands completed successfully in an
  ;; isolated JVM. The test runner owns the outer loud liveness backstop.
  (.get (.onExit process))
  {:seon.dev.fresh-operator-test/completed? true
   :seon.dev.fresh-operator-test/exit (.exitValue process)
   :seon.dev.fresh-operator-test/output @output-future})

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
        (update record :seon.boot/start-instant
                (fn [value]
                  (java.util.Date/from
                   (.plusMillis (.toInstant ^java.util.Date value) 1))))]
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
        {:seon.operator.process-record/generation (random-uuid)
         :seon.boot/pid 1
         :seon.boot/start-instant #inst "2026-08-01T00:00:00Z"
         :seon.operator.process-record/root (.getCanonicalPath foreign-root)
         :seon.operator.process-record/log (str (io/file foreign-root "foreign.log"))}]
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
      (finally
        (operator-private-value
         'clear-process-record! (str foreign-root) record)
        (delete-recursively! root)
        (delete-recursively! foreign-root)))))

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
        {:seon.operator.process-record/generation generation
         :seon.boot/pid 2147483647
         :seon.boot/start-instant #inst "2026-08-05T00:00:00Z"
         :seon.operator.process-record/root (.getCanonicalPath root)
         :seon.operator.process-record/log (str (io/file root "dead.log"))}]
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
        owner-pid (str (.pid (java.lang.ProcessHandle/current)))
        record
        {:seon.operator.process-record/generation (random-uuid)
         :seon.boot/pid 1
         :seon.boot/start-instant #inst "2026-08-01T00:00:00Z"
         :seon.operator.process-record/root (.getCanonicalPath root)
         :seon.operator.process-record/log (str (io/file root "isolated.log"))}]
    (try
      (is (nil? (operator-private-value
                 'ephemeral-owner (str project-root) owner-pid))
          "the shared repository root ignores a lane's ephemeral owner")
      (is (= (long (parse-long owner-pid))
             (:seon.boot/pid
              (operator-private-value
               'ephemeral-owner (str root) owner-pid)))
          "an explicit isolated root retains the lane owner identity")
      (operator.state/claim-root!
       (.getCanonicalPath project-root)
       (.getCanonicalPath root)
       nil
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
             (io/file (str (operator.state/root-lifecycle-lock-path root))))
            "the selected root owns the lifecycle lock its command took"))
      (finally
        (operator-private-value
         'clear-process-record! (str root) record)
        (.delete
         (io/file
          (str (operator.state/root-claim-path project-root root))))
        (delete-recursively! root)))))

(deftest status-aggregates-invalid-external-claim-causes
  (let [repository-root (fresh-root)
        absent-root (io/file repository-root "absent-root")
        present-root (io/file repository-root "present-root")]
    (try
      (.mkdirs present-root)
      (doseq [root [absent-root present-root]]
        (operator.state/write-edn!
         (operator.state/root-claim-path repository-root root)
         {:seon.operator.claim/id
          (operator.state/root-claim-id root)
          :seon.operator.claim/root (.getCanonicalPath root)}))
      (let [errors (:errors (operator.state/root-claims repository-root))
            line (operator-private-value 'invalid-claims-line errors)]
        (is (= (str "invalid external claims: 2 total "
                    "(1 orphaned for absent roots; 1 malformed); "
                    "reclaim with `bin/seon reset --force`.")
               line))
        (is (not (str/includes? line (.getCanonicalPath absent-root)))
            "the status face aggregates evidence instead of repeating paths"))
      (finally
        (delete-recursively! repository-root)))))

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
      (let [failure
            {:seon.dev.fresh-operator-test/value winner
             :seon.dev.fresh-operator-test/output
             (deref child-output 10000
                    "The anchor output reader did not finish.")}]
        (throw (ex-info (str "The anchor returned malformed readiness: "
                             (pr-str failure))
                        failure))))
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
              (let [original-resolve# seon.cluster/resolve-bootstrap]
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
             ["clojure" "-J-Dseon.operator.claimed=true"
              "-M:test" "-e" code])
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

(deftest prepl-response-progress-resets-the-silence-backstop
  (let [{:seon.dev.fresh-operator-test/keys [outcome]}
        (prepl-response-simulation :progress)]
    (is (nil? (:seon.dev.fresh-operator-test/message outcome))
        (pr-str outcome))
    (is (<= 320 (:seon.dev.fresh-operator-test/elapsed-ms outcome))
        "the response outlived one silence interval")
    (is (= [:out :out :out :ret]
           (mapv :tag
                 (:seon.dev.fresh-operator-test/value outcome))))
    (is (= ["analysis\n" "schema population\n" "branch publication\n"]
           (into []
                 (comp (filter #(= :out (:tag %))) (map :val))
                 (:seon.dev.fresh-operator-test/observed outcome)))
        "every publication progress event remains observable")))

(deftest prepl-response-silence-still-trips-the-backstop
  (let [{:seon.dev.fresh-operator-test/keys [output outcome]}
        (prepl-response-simulation :silent)]
    (is (= :seon.fresh-operator/prepl-response-silent
           (get-in outcome
                   [:seon.dev.fresh-operator-test/data
                    :seon.error/kind])))
    (is (= 250
           (get-in outcome
                   [:seon.dev.fresh-operator-test/data
                    :seon.fresh-operator/silence-backstop-ms])))
    (is (str/includes?
         output
         "operator event silence backstop fired: prepl response was silent"))))

(deftest ^{:seon.test/long
           "110.837 s pool: complete publication JVM plus named fork/refork, start, readiness, and store read-back."}
  init-owns-current-source-and-dormant-cluster-lifecycle
  (let [root (fresh-root)
        store-dir (str (io/file root "data" "store"))
        name "init-command"
        published-commit (atom nil)
        current-digest
        (:seon.source/digest (cluster/source-snapshot))]
    (try
      (let [bare (run-operator root "init")]
        (is (::completed? bare) (::output bare))
        (is (= 0 (::exit bare)) (::output bare))
        (is (str/includes? (::output bare) (str source/current-branch))
            (::output bare))
        (is (str/includes? (::output bare) current-digest)
            (::output bare))
        (let [opened (store/open-store! {:seon.store/dir store-dir})]
          (try
            (reset!
             published-commit
             (registry/branch-commit-id
              {:seon.store/store opened
               :seon.store/branch source/current-branch}))
            (finally
              (store/release-store! opened)))))
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
                "bare init does not invent a default cluster")
            (is (= @published-commit
                   (registry/branch-commit-id
                    {:seon.store/store opened
                     :seon.store/branch source/current-branch}))
                "named fork/refork operations consume, but never republish, current-src"))
          (finally
            (store/release-store! opened))))
      (let [started (run-operator root "start" name)
            advertisement
            (when (zero? (::exit started))
              (edn/read-string
               (slurp (io/file root "data" "clusters" name "prepl.edn"))))]
        (is (::completed? started) (::output started))
        (is (= 0 (::exit started)) (::output started))
        (is (= "true"
               (prepl-eval
                advertisement
                (pr-str
                 `(some?
                   (:seon.boot/ready-ms
                    (get @seon.operator.runtime/running-instances ~name))))))
            (::output started)))
      (finally
        (try
          (run-operator root "down" "--force")
          (catch Throwable _))
        (delete-recursively! root)))))

(deftest ^{:seon.test/long
           "110.095 s pool: real init/start JVM, live namespace damage, republication/reload, and admission proof."}
  live-init-reloads-schema-runtime-and-moved-predicate-owners-before-admission
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
                  (require 'seon.schema 'seon.sci.eval)
                  (ns-unmap 'seon.schema (symbol "call-with-forms"))
                  (ns-unmap 'seon.sci.eval (symbol "projection-state"))
                  {:seon.dev.fresh-operator-test/schema-api-loaded?
                   (boolean
                    (ns-resolve 'seon.schema (symbol "call-with-forms")))
                   :seon.dev.fresh-operator-test/projection-api-loaded?
                   (boolean
                    (ns-resolve 'seon.sci.eval
                                (symbol "projection-state")))}))))]
        (is (false? (::schema-api-loaded? stale)) stale)
        (is (false? (::projection-api-loaded? stale)) stale))
      ;; A loaded predicate owner used to be able to lose its predicates: the
      ;; process-global symbol->function cache could be rewritten out from
      ;; under it, so "loaded" and "resolvable" were two different facts. They
      ;; are one fact now — resolution reads the Var the qualified symbol
      ;; names — so this staging has nothing left to stage, and the class it
      ;; simulated is unrepresentable rather than merely untested.
      (let [advertisement
            (edn/read-string
             (slurp (io/file root "data" "clusters"
                             cluster-name "prepl.edn")))
            resolved
            (edn/read-string
             (prepl-eval
              advertisement
              (pr-str
               `(do
                  (require 'seon.db 'seon.schema)
                  {:seon.dev.fresh-operator-test/owner-loaded?
                   (boolean (find-ns 'seon.db))
                   :seon.dev.fresh-operator-test/connection-registered?
                   (seon.schema/core-predicate-registered?
                    'seon.db/connection?)}))))]
        (is (true? (::owner-loaded? resolved)) resolved)
        (is (true? (::connection-registered? resolved)) resolved))
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

(deftest ^{:seon.test/long
           "94.568 s pool: destructive reset performs complete republication and default refork in a real JVM."}
  source-less-root-reset-republishes-and-reforks-default
  (let [root (fresh-root)
        store-dir (str (io/file root "data" "store"))]
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

(deftest reset-discards-only-enumerated-unreadable-claims-after-the-flock
  (let [root (fresh-root)
        store-dir (str (io/file root "data" "store"))
        exact (io/file root "claims" "unreadable.edn")
        survivor (io/file root "claims" "survivor.edn")
        errors [{:seon.fresh-operator/path (.getCanonicalPath exact)
                 :seon.fresh-operator/error "invalid claim"}]
        opened (store/open-store! {:seon.store/dir store-dir})]
    (try
      (.mkdirs (.getParentFile exact))
      (spit exact "unreadable")
      (spit survivor "survivor")
      (let [refused
            (operator-private-outcome
             'confirm-exclusive-store-flock-free! (str root))]
        (is (= "Refusing reset because a live JVM holds the process-root store flock."
               (::message refused)))
        (is (.exists exact)
            "a held flock leaves the unreadable claim untouched"))
      (finally
        (store/release-store! opened)))
    (try
      (is (= (operator.state/store-lock-path store-dir)
             (operator-private-value
              'confirm-exclusive-store-flock-free! (str root))))
      (operator-private-value 'discard-unreadable-process-records! errors)
      (is (not (.exists exact))
          "the exact enumerated unreadable claim was discarded")
      (is (.exists survivor)
          "an unenumerated sibling was not glob-deleted")
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long
           "92.850 s pool: init/start JVM, SIGKILL, exact process-record fencing, complete reset/republication."}
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
            generation (:seon.operator.process-record/generation record)
            legacy-file
            (io/file root "data" "clusters" "processes"
                     (str generation ".edn"))
            handle
            (some-> (java.lang.ProcessHandle/of
                     (long (:seon.boot/pid record)))
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

(deftest ^{:seon.test/long
           "114.689 s pool: init/fork, two real JVM identities, stop/restart, and populated-store read-back."}
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

(deftest ^{:seon.test/long
           "53.876 s pool: real Malli collection/instrumentation refreshes a stale start wrapper before add."}
  add-refreshes-a-genuinely-stale-wrapper-before-current-start
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
        effective-projections (atom [])
        projection (schema/build-projection (schema/snapshot))
        projection-state
        (env/environment-state
         (env/environment
          {:seon.boot/cluster-name "live"
           :seon.db/basis-t 0
           :seon.schema/projection projection}))
        instance-context
        {:seon.sci.eval/projection-state projection-state}
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
          {:seon.boot/cluster-connection connection
           :seon.sci.eval/ctx instance-context})
        config/effective
        (fn [_ _]
          (swap! effective-projections conj
                 (some? (schema/handed-projection)))
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
           :seon.sci.eval/ctx instance-context
           :seon.boot/advertisement
           {:seon.boot/cluster-name "live"}}})
        (is (= "scratch" (eval (read-string form))))
        (is (= [current-request] @start-calls)
            "the pre-start apply! replaced the stale wrapper before start")
        (is (= [true true] @effective-projections)
            "both operator config reads run inside the cluster projection"))
      (finally
        (mi/unstrument! {:filters [start-filter]})
        (alter-meta! start-var (constantly start-meta))
        (reset! (var-get instances-var) instances-before)))))

(deftest start-sweep-refusal-is-retryable-and-unwinds-the-partial-instance
  (let [root (fresh-root)
        instance {:seon.boot/config
                  {:seon.boot/root (str (io/file root "data" "clusters"))
                   :seon.boot/cluster-name "blocked"}}
        form (operator-private-value 'add-form (str root) "blocked" {})
        stopped (atom [])
        failure
        (ex-info
         "The cluster instance failed above the REPL."
         {:seon.error/kind :seon.boot/refused
          :seon.boot/instance instance}
         (ex-info
          "A reachability sweep is in progress."
          {:seon.error/kind :sweep-in-progress
           :seon.error/retryable? true
           :seon.store/id (random-uuid)
           :datahike.gc-guard/maintenance-receipt
           {:seon.maintenance.receipt/id "sweep-1"}}))]
    (with-redefs [cluster/start! (fn [_request] (throw failure))
                  cluster/stop! (fn [stopped-instance]
                                  (swap! stopped conj stopped-instance))
                  instrument/apply!
                  (fn [_request]
                    (throw (ex-info "a refused start must not instrument" {})))]
      (let [refusal (eval (read-string form))]
        (is (= :sweep-in-progress (:seon.error/kind refusal)))
        (is (true? (:seon.error/retryable? refusal)))
        (is (= [instance] @stopped))))))

(deftest ^{:seon.test/long
           "93.065 s pool: complete init plus fresh-process whole-image instrumentation and readiness proof."}
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
                  "config" "program" "work-launcher" "agents" "web"]
                 anchor-phases)
              "the readiness socket reports every published boot boundary")
          (is add-completed? "the generated add form completed")
          (is (= 0 add-exit) add-output)
          (is scratch-ready?
              "the added scratch cluster published its web URL")))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long
           "90.430 s pool: refused cold boot, complete init, cached real-JVM boot, and every readiness phase."}
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

(deftest isolated-operator-roots-do-not-serialize-on-one-lifecycle-lock
  (let [held-root (fresh-root)
        other-root (fresh-root)
        held-lock (operator.state/root-lifecycle-lock-path (str held-root))
        other-lock (operator.state/root-lifecycle-lock-path (str other-root))
        acquired (promise)
        release (promise)
        holder
        (future
          (operator.state/with-lifecycle-lock!
           {:seon.operator.lock/path held-lock
            :seon.operator.lock/command "the regression's holder"
            :seon.operator.lock/acquisition-timeout-ms 2000
            :seon.operator.lock/hold-timeout-ms 60000}
           (fn []
             (deliver acquired true)
             (deref release 60000 :backstop))))]
    (try
      (is (not= (str held-lock) (str other-lock))
          "each operator root owns its own lifecycle lock file")
      (is (true? (deref acquired 10000 false))
          "the regression could not take the held root's lifecycle lock")
      (testing "a command in another root runs while this root's lock is held"
        (let [outcome (run-operator other-root "down")]
          (is (::completed? outcome) (::output outcome))
          (is (= 0 (::exit outcome)) (::output outcome))
          (is (not (str/includes? (::output outcome)
                                  "for the operator lifecycle lock"))
              (::output outcome))
          (is (not (realized? release))
              "the isolated root finished before the held lock was released")))
      (testing "a second command in the SAME root waits loudly, then refuses"
        (let [captured (java.io.StringWriter.)
              refusal
              (binding [*out* captured]
                (try
                  (operator.state/with-lifecycle-lock!
                   {:seon.operator.lock/path held-lock
                    :seon.operator.lock/command "a second same-root command"
                    :seon.operator.lock/acquisition-timeout-ms 2000
                    :seon.operator.lock/hold-timeout-ms 2000}
                   (fn [] ::ran))
                  (catch clojure.lang.ExceptionInfo error
                    (ex-data error))))
              announcement (str captured)
              pid (:seon.boot/pid (operator.state/current-process-identity))]
          (is (= :seon.operator/lock-acquisition-timeout
                 (:seon.error/kind refusal))
              (pr-str refusal))
          (is (= pid (get-in refusal [:seon.operator.lock/holder
                                      :seon.boot/pid]))
              (pr-str refusal))
          (is (str/includes? announcement "for the operator lifecycle lock")
              announcement)
          (is (str/includes? announcement (str "pid " pid)) announcement)
          (is (str/includes? announcement "the regression's holder")
              announcement)))
      (testing "a real command binds to the SELECTED root's lifecycle lock"
        (let [process
              (.start
               (doto (ProcessBuilder.
                      ^java.util.List (operator-command held-root "down"))
                 (.directory project-root)
                 (.redirectErrorStream true)))
              output-future (process-output process)
              pid (:seon.boot/pid (operator.state/current-process-identity))]
          (is (not (.waitFor process 2500 TimeUnit/MILLISECONDS))
              "the command did not wait for the selected root's held lock")
          (deliver release :released)
          (let [outcome (await-process! process output-future
                                        "The queued fresh operator")]
            (is (= 0 (::exit outcome)) (::output outcome))
            (is (str/includes? (::output outcome)
                               "for the operator lifecycle lock")
                (::output outcome))
            (is (str/includes? (::output outcome) (str "pid " pid))
                (::output outcome)))))
      (finally
        (deliver release :released)
        (deref holder 30000 :abandoned)
        (delete-recursively! held-root)
        (delete-recursively! other-root)))))
