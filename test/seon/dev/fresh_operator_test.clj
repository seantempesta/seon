(ns seon.dev.fresh-operator-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [malli.instrument :as mi]
            [seon.cluster :as cluster]
            [seon.cluster.ancestor :as ancestor]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.instrument :as instrument]
            [seon.test-support :as test-support])
  (:import [java.net ServerSocket]
           [java.nio.file Files]
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

(defn- runnable-root!
  [root]
  (doseq [path ["config" "deps.edn" "reference-code" "resources"
                "src" "test"]]
    (Files/createSymbolicLink
     (.toPath (io/file root path))
     (.toPath (io/file project-root path))
     (make-array java.nio.file.attribute.FileAttribute 0)))
  root)

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
            "--classpath" (str (io/file project-root "script"))
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
    (edn/read-string output)))

(defn- process-output
  [^Process process]
  (future
    (try
      (slurp (.getInputStream process))
      (catch java.io.IOException error
        (str "The child output stream closed after termination: "
             (ex-message error))))))

(defn- run-operator
  [root & arguments]
  (let [process
        (.start
         (doto (ProcessBuilder.
                ^java.util.List
                (apply operator-command root arguments))
           (.directory project-root)
           (.redirectErrorStream true)))
        output-future (process-output process)
        completed? (.waitFor process 30 TimeUnit/SECONDS)
        _ (when-not completed? (.destroyForcibly process))]
    {:seon.dev.fresh-operator-test/completed? completed?
     :seon.dev.fresh-operator-test/exit
     (when completed? (.exitValue process))
     :seon.dev.fresh-operator-test/output
     (deref output-future 10000
            "The operator output reader did not finish.")}))

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
    (.setSoTimeout ready-server 30000)
    (let [launch-form
          (operator-private-value
           'launch-form "anchor" {} (.getLocalPort ready-server))
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
        (with-open [ready-socket (.accept ready-server)
                    ready-reader (io/reader ready-socket)]
          (when-not (= "ready"
                       (.readLine ^java.io.BufferedReader ready-reader))
            (throw (ex-info "The anchor returned malformed readiness." {}))))
        (let [anchor-advertisement
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
            (.destroyForcibly child))
          (.waitFor child 10 TimeUnit/SECONDS)
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

(deftest destructive-reset-selection-requires-one-unambiguous-cluster
  (let [row
        (fn [name]
          {:seon.fresh-operator/name name
           :seon.fresh-operator/operator-root? true})]
    (is (= "only"
           (operator-private-value
            'select-destructive-name [(row "only")] nil "reset")))
    (let [outcome
          (operator-private-outcome
           'select-destructive-name
           [(row "alpha") (row "beta")]
           nil
           "reset")]
      (is (str/includes?
           (:seon.dev.fresh-operator-test/message outcome)
           "Refusing ambiguous `reset` because it destroys cluster data"))
      (is (= ["alpha" "beta"]
             (get-in outcome
                     [:seon.dev.fresh-operator-test/data
                      :seon.fresh-operator/candidates]))))))

(deftest bare-index-refreshes-the-baseline-and-never-selects-default
  (let [root (runnable-root! (fresh-root))
        store-dir (str (io/file root "data" "clusters" "store"))]
    (try
      (let [result (run-operator root "index")]
        (is (::completed? result) (::output result))
        (is (= 0 (::exit result)) (::output result))
        (is (str/includes? (::output result) "● baseline source index")
            (::output result)))
      (let [opened (store/open-store! {:seon.store/dir store-dir})]
        (try
          (let [current-digest
                (ancestor/digest
                 {:seon.ancestor/roots cluster/ancestor-roots})
                roster (registry/roster opened)]
            (is (contains? roster (ancestor/ancestor-branch current-digest)))
            (is (not (contains? roster :cluster-default))
                "bare index is the ruling-28 exception to default selection"))
          (finally
            (store/release-store! opened))))
      (finally
        (delete-recursively! root)))))

(deftest index-command-primes-one-live-cluster-through-the-one-indexer
  (let [root (fresh-root)
        cluster-root (str (io/file root "data" "clusters"))
        name "index-command"
        instance
        (cluster/start! {:seon.boot/cluster-name name
                         :seon.boot/root cluster-root})
        connection (:seon.boot/cluster-connection instance)
        stale-digest (apply str (repeat 64 "e"))]
    (try
      (let [ancestor
            (d/q '[:find ?ancestor .
                   :where [?ancestor :seon.ancestor/digest]]
                 @connection)
            functions
            (d/q '[:find [?function ...]
                   :where [?function :seon.fn/sym]]
                 @connection)]
        (d/transact
         connection
         (into
          [[:db.fn/retractAttribute ancestor :seon.ancestor/digest]
           {:db/id ancestor :seon.ancestor/digest stale-digest}]
          (map (fn [function] [:db.fn/retractEntity function]))
          functions)))
      (let [result (run-operator root "index" name)]
        (is (::completed? result) (::output result))
        (is (= 0 (::exit result)) (::output result))
        (is (str/includes? (::output result)
                           (str "● " name " source index"))
            (::output result))
        (is (pos?
             (or
              (d/q '[:find (count ?function) .
                     :where [?function :seon.fn/sym]]
                   @connection)
              0)))
        (is (not= stale-digest
                  (d/q '[:find ?digest .
                         :where [_ :seon.ancestor/digest ?digest]]
                       @connection))))
      (let [basis (:max-tx @connection)
            converged (run-operator root "index" name)]
        (is (::completed? converged) (::output converged))
        (is (= 0 (::exit converged)) (::output converged))
        (is (str/includes? (::output converged)
                           "converged? true")
            (::output converged))
        (is (= basis (:max-tx @connection))
            "a converged operator prime writes no transaction"))
      (finally
        (cluster/stop! instance)
        (delete-recursively! root)))))

(deftest reset-command-destroys-history-and-reforks-a-current-cluster
  (let [root (runnable-root! (fresh-root))
        name "reset-command"]
    (try
      (let [started (run-operator root "start" name)]
        (is (::completed? started) (::output started))
        (is (= 0 (::exit started)) (::output started)))
      (let [advertisement
            (edn/read-string
             (slurp (io/file root "data" "clusters" name "prepl.edn")))]
        (prepl-eval
         advertisement
         (pr-str
          `(do
             (require 'datahike.api)
             (let [instances#
                   @@(ns-resolve 'seon.cluster
                                 (symbol "running-instances"))
                   connection#
                   (:seon.boot/cluster-connection
                    (get instances# ~name))]
               (datahike.api/transact
                connection#
                [{:seon.cluster.message/id
                  "operator-reset-destroys"}]))))))
      (let [reset-result (run-operator root "reset" name)]
        (is (::completed? reset-result) (::output reset-result))
        (is (= 0 (::exit reset-result)) (::output reset-result))
        (is (str/includes?
             (::output reset-result)
             (str "● " name " reset destroyed its old branch"))
            (::output reset-result)))
      (let [restarted (run-operator root "start" name)]
        (is (::completed? restarted) (::output restarted))
        (is (= 0 (::exit restarted)) (::output restarted)))
      (let [advertisement
            (edn/read-string
             (slurp (io/file root "data" "clusters" name "prepl.edn")))
            history
            (prepl-eval
             advertisement
             (pr-str
              `(let [instances#
                     @@(ns-resolve 'seon.cluster
                                   (symbol "running-instances"))
                     connection#
                     (:seon.boot/cluster-connection
                      (get instances# ~name))]
                 (datahike.api/q
                  '[:find ?message .
                    :where
                    [?message :seon.cluster.message/id
                     "operator-reset-destroys"]]
                  @connection#))))]
        (is (= "nil" history)
            "the replacement branch contains none of the destroyed history"))
      (finally
        (run-operator root "stop" name)
        (delete-recursively! root)))))

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

(deftest fresh-process-loads-schema-before-every-operator-instrumentation
  (let [root (fresh-root)]
    (try
      (let [{::keys [anchor-ready? add-completed? add-exit add-output
                     scratch-ready?]}
            (fresh-process-operator-paths root)]
        (is anchor-ready?
            "the generated launch form instrumented before publishing ready")
        (is add-completed? "the generated add form exceeded thirty seconds")
        (is (= 0 add-exit) add-output)
        (is scratch-ready?
            "the added scratch cluster published its web URL"))
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
