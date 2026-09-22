(ns seon.cluster.boot-test
  "Real client/store exclusion drills. Child events are bounded and identities retained."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.dev.mcp :as mcp]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.cluster.process :as process]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.fs :as fs]
            [seon.id :as id]
            [seon.instrument :as instrument]
            [seon.operator :as client]
            [seon.operator.runtime :as runtime]
            [seon.test-support :as support])
  (:import [java.nio.file Files LinkOption]
           [java.util.concurrent TimeUnit]))

(def ^:private cold-ms (client/operator-boot-bound-ms {}))
(def ^:private event-ms (* 1000 support/event-backstop-seconds))

(defn- await!
  ([event label] (await! event label event-ms))
  ([event label bound]
   (let [value (deref event bound ::missing)]
     (when (= ::missing value) (throw (ex-info "Missing child event." {:seon.probe/event label})))
     value)))

(defn- launch-process! [children argv]
  (let [child (.start (ProcessBuilder. ^java.util.List argv))
        _ (swap! children conj (client/identity-of (.toHandle child)))
        early (promise)
        stdout (future (slurp (.getInputStream child)))
        stderr (future
                 (try (with-open [reader (io/reader (.getErrorStream child))]
                   (reduce (fn [lines line]
                             (when (str/starts-with? line "REPL ")
                               (let [advertisement (edn/read-string (subs line 5))]
                                 (swap! children conj
                                        (select-keys advertisement [:seon.boot/pid :seon.boot/start-instant]))
                                 (println "B1b child" (pr-str advertisement))
                                 (deliver early advertisement)))
                             (conj lines line)) [] (line-seq reader)))
                   (finally (deliver early ::child-closed))))]
    {:seon.probe/process child :seon.probe/early early
     :seon.probe/out stdout :seon.probe/err stderr}))

(defn- cli! [root children arguments]
  (launch-process! children (into ["bin/seon" "--root" root] arguments)))

(defn- completed!
  ([launch] (completed! launch cold-ms))
  ([launch bound]
  (let [child (:seon.probe/process launch)]
    (.get (.onExit child) bound TimeUnit/MILLISECONDS)
    (let [out (await! (:seon.probe/out launch) :stdout)
          err (await! (:seon.probe/err launch) :stderr)]
      {:seon.probe/exit (.exitValue child)
       :seon.probe/value (when-not (str/blank? out) (edn/read-string out))
       :seon.probe/err err}))))

(defn- with-root! [body]
  (let [root (.getCanonicalPath (io/file "tmp" (str "b1b-" (id/id))))
        sentinel (io/file (str root "-sentinel"))
        children (atom #{})
        primary-failure (atom nil)]
    (.mkdirs (io/file root))
    (spit sentinel "outside")
    (try
      (Files/createSymbolicLink (.toPath (io/file root "external-link"))
                               (.toPath sentinel)
                               (make-array java.nio.file.attribute.FileAttribute 0))
      (body root children sentinel)
      (catch Throwable cause (reset! primary-failure cause) (throw cause))
      (finally
        (let [failures (atom [])
              attempt! (fn [operation]
                         (try (operation) (catch Throwable cause (swap! failures conj cause))))]
          (doseq [identity @children]
            (attempt! #(client/terminate! identity event-ms)))
          (doseq [[_ instance] @runtime/running-instances
                  :when (and (map? instance)
                             (= (str (io/file root "data/clusters"))
                                (get-in instance [:seon.boot/config :seon.boot/root])))]
            (attempt! #(boot/stop! instance)))
          ;; Never remove a root if an owned subject's exit/release is unknown.
          (when (empty? @failures)
            (attempt! #(fs/delete-recursively! root root)))
          (attempt! #(is (= "outside" (slurp sentinel)) "Cleanup never follows symlinks."))
          (when (empty? @failures) (.delete sentinel))
          (when (seq @failures)
            (throw (ex-info "Owned drill cleanup failed; root retained."
                            {:seon.probe/root root :seon.probe/cleanup-errors (mapv ex-message @failures)}
                            (or @primary-failure (first @failures))))))))))

(defn- with-published-root! [body]
  (with-root!
    (fn [root children sentinel]
      (support/populate-published-operator-root!
       root {:seon.test/fixture-observation
             "Store exclusion, retained lock inode and destructive reset require a private physical store, not a branch."})
      (body root children sentinel))))

(defn- request [root command]
  {:seon.operator/managed-root root :seon.operator/command command})

(defn- evaluate!
  ([root form] (evaluate! root form event-ms))
  ([root form bound] (client/prepl-value! (client/advertisement root nil) form bound)))

(defn- started! [root children]
  (let [result (completed! (cli! root children ["start"]))]
    (is (zero? (:seon.probe/exit result)) (pr-str result))
    (when-not (zero? (:seon.probe/exit result))
      (throw (ex-info "Scratch boot failed." result)))
    (:seon.probe/value result)))

(deftest ^{:seon.test/platform "Cold empty-root boot is the process entry dependency."
           :seon.test/long "Real empty-root JVM boot and complete program publication."
           :seon.test/long-ms 300000}
  cold-start
  (with-root!
    (fn [root children _]
      (let [launch (cli! root children ["start"])
            early (await! (:seon.probe/early launch) :early-repl)]
        (is (= 2 (client/prepl-value! early "(+ 1 1)" event-ms)))
        (let [result (completed! launch)
              ready (get-in result [:seon.probe/value :seon.boot/readiness])]
          (is (zero? (:seon.probe/exit result)) (pr-str result))
          (is (empty? (:seon.boot/missing-layers ready)))
          (is (client/matching-handle early))
          (is (= (select-keys early [:seon.boot/pid :seon.boot/start-instant])
                 (select-keys (client/advertisement root "default") [:seon.boot/pid :seon.boot/start-instant])))
          (is (string? (:seon.render.web/url ready)))
          (let [http (.openConnection (.toURL (java.net.URI/create (:seon.render.web/url ready))))]
            (.setConnectTimeout http event-ms) (.setReadTimeout http event-ms)
            (with-open [input (.getInputStream http)]
              (is (str/includes? (slurp input) "html"))))
          (is (= "default" (evaluate! root "(:seon.cluster/name (seon.db/pull (seon.db/db (seon.cluster.boot/connection \"default\")) [:seon.cluster/name] [:seon.cluster/name \"default\"]))")))
          (is (= 4 (evaluate! root "(+ 2 2)")))
          (let [sci (client/prepl-value! early
                                          (#'mcp/sci-evaluation-form "(+ 1 1)" "default" 'user true)
                                          event-ms)]
            (is (= "2" (get-in sci [:seon.dev.mcp/value :seon.dev.mcp/text]))))
          (let [partial (evaluate! root
                         "(with-redefs [seon.cluster/serve! (fn [& _] (throw (ex-info \"injected web failure\" {})))] (try (seon.cluster.boot/start! {:seon.boot/root (get-in (get @seon.operator.runtime/running-instances \"default\") [:seon.boot/config :seon.boot/root]) :seon.boot/cluster-name \"partial\"}) (catch Throwable e (seon.cluster.boot/readiness (:seon.boot/instance (ex-data e))))))" 120000)]
            (is (some #{:seon.render.web/served} (:seon.boot/missing-layers partial)))
            (is (= 2 (client/prepl-value! partial "(+ 1 1)" event-ms)))))))))

(defn- cold-launch!
  ([root children destroy?] (cold-launch! root children destroy? "default"))
  ([root children destroy? cluster-name]
  (let [request (cond-> {:seon.operator/managed-root root :seon.operator/command :start
                         :seon.boot/cluster-name cluster-name}
                  destroy? (assoc :seon.store/destroy? true))
        form `(do (require '~'seon.operator)
                  (let [result# (~'seon.operator/launch! '~request)]
                    (prn result#) (shutdown-agents)
                    (System/exit (if (:seon.error/at result#) 1 0))))]
    (launch-process! children ["bb" "--classpath" "script:src:resources" "-e" (pr-str form)]))))

(defn- lock-refused! [launch]
  (let [result (completed! launch)]
    (is (= 1 (:seon.probe/exit result)) (pr-str result))
    (is (some #(and (map? %)
                    (= :seon.cluster.store/held-elsewhere (:seon.cluster.store/rule %)))
              (tree-seq coll? seq (:seon.probe/value result)))
        (pr-str result))
    (let [identity (await! (:seon.probe/early launch) :loser-identity)]
      (is (nil? (client/matching-handle identity)) "Lock refusal includes actual loser exit."))
    result))

(defn- with-controlled-child! [root children mode cluster-name body]
  (with-open [callback (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))]
    (.setSoTimeout callback (int event-ms))
    (let [launch (launch-process!
                  children ["clojure" (str "-J-Dseon.operator.root=" root)
                            "-M:dev:test" "-m" "seon.cluster.boot-drill-child"
                            root (str (.getLocalPort callback)) (str cold-ms) mode cluster-name])]
      (with-open [socket (.accept callback)
                  reader (java.io.BufferedReader. (io/reader socket))
                  writer (io/writer socket)]
        (.setSoTimeout socket (int cold-ms))
        (let [identity (edn/read-string (.readLine reader))]
          (swap! children conj (select-keys identity [:seon.boot/pid :seon.boot/start-instant]))
          (println "B1b controlled child" (pr-str identity))
          (body launch socket reader writer identity))))))

(deftest ^{:seon.test/long "Two real cold JVM starts compete while the winner retains its acquired store before advertisement."
           :seon.test/long-ms 180000}
  concurrent-start
  (with-published-root!
    (fn [root children _]
      (with-controlled-child!
        root children "start" "default"
        (fn [_ socket reader writer identity]
          (is (= :store-entry (:seon.probe/event (edn/read-string (.readLine reader)))))
          (let [advertisement-file (io/file root "data/clusters/default/prepl.edn")]
            (is (not (.exists advertisement-file)))
            (lock-refused! (cold-launch! root children false))
            (is (not (.exists advertisement-file)) "The losing same-name child publishes no advertisement.")
            (.write writer "continue\n") (.flush writer)
            (let [ready (edn/read-string (.readLine reader))
                  advertisement (client/advertisement root "default")]
              (is (= :ready (:seon.probe/event ready)) (pr-str ready))
              (is (= (select-keys identity [:seon.boot/pid :seon.boot/start-instant])
                     (select-keys advertisement [:seon.boot/pid :seon.boot/start-instant])))
              (lock-refused! (cold-launch! root children false))
              (is (= advertisement (client/advertisement root "default")))
              (is (= 2 (client/prepl-value! advertisement "(+ 1 1)" event-ms))))))))))

(deftest ^{:seon.test/long "Two canonical hosted instances, ordered release and injected branch-release refusal."
           :seon.test/long-ms 120000}
  stop-instance
  (with-published-root!
    (fn [root _ _]
      (let [name (str "a-" (id/id)) sibling (str "b-" (id/id))
            config {:seon.boot/root (str root "/data/clusters")}
            old (boot/start! (assoc config :seon.boot/cluster-name name))
            other (boot/start! (assoc config :seon.boot/cluster-name sibling))]
        (boot/stop! old)
        (is (= 2 (client/prepl-value! (:seon.boot/advertisement other) "(+ 1 1)" event-ms)))
        (let [replacement (boot/start! (assoc config :seon.boot/cluster-name name))]
          (boot/stop! old)
          (is (identical? replacement (get @runtime/running-instances name)))
          (let [release store/release-branch!]
            (with-redefs [store/release-branch!
                          (fn [connection]
                            (if (identical? connection (:seon.boot/cluster-connection replacement))
                              (throw (ex-info "injected release refusal" {}))
                              (release connection)))]
              (is (thrown? clojure.lang.ExceptionInfo (boot/stop! replacement))))
            (is (.isValid (:seon.store/lock (:seon.store/store replacement))))
            (is (= 2 (client/prepl-value! (:seon.boot/advertisement replacement) "(+ 1 1)" event-ms)))))))))

(deftest ^{:seon.test/long "A sole-instance stop flushes its reply, exits, and permits one cold replacement."
           :seon.test/long-ms 120000}
  sole-stop-exits-and-restarts
  (with-published-root!
    (fn [root children _]
      (let [first-start (started! root children)
            first-identity (select-keys first-start [:seon.boot/pid :seon.boot/start-instant])
            first-handle (client/matching-handle first-identity)
            stopped (completed! (cli! root children ["stop"]))]
        (is (zero? (:seon.probe/exit stopped)) (pr-str stopped))
        (is (= {:seon.boot/cluster-name "default" :seon.operator/stopped? true
                :seon.operator/process-exit? true}
               (:seon.probe/value stopped)))
        (.get (.onExit first-handle) event-ms TimeUnit/MILLISECONDS)
        (is (nil? (client/matching-handle first-identity)))
        (let [second-start (started! root children)
              second-identity (select-keys second-start [:seon.boot/pid :seon.boot/start-instant])]
          (is (not= (:seon.boot/pid first-identity) (:seon.boot/pid second-identity)))
          (is (= 2 (evaluate! root "(+ 1 1)"))))))))

(deftest ^{:seon.test/long "Graceful down returns one plain-EDN identity map before process exit."
           :seon.test/long-ms 120000}
  down-reply-is-readable-edn
  (with-published-root!
    (fn [root children _]
      (let [started (started! root children)
            identity (select-keys started [:seon.boot/pid :seon.boot/start-instant])
            handle (client/matching-handle identity)
            down (completed! (cli! root children ["down"]))
            reply (:seon.probe/value down)]
        (is (zero? (:seon.probe/exit down)) (pr-str down))
        (is (= {:seon.operator/stopped-processes [identity]
                :seon.operator/process-exit? true} reply))
        (is (= reply (edn/read-string (pr-str reply))))
        (.get (.onExit handle) event-ms TimeUnit/MILLISECONDS)
        (is (nil? (client/matching-handle identity)))))))

(deftest ^{:seon.test/long "Real owned JVM receives SIGSTOP; down must prove exit without a REPL reply."
           :seon.test/long-ms 120000}
  down-unresponsive
  (with-published-root!
    (fn [root children _]
      (started! root children)
      (let [identity (select-keys (client/advertisement root nil) [:seon.boot/pid :seon.boot/start-instant])]
        (is (thrown? clojure.lang.ExceptionInfo
                     (client/terminate! (assoc identity :seon.boot/start-instant (java.util.Date. 0)) event-ms)))
        (is (thrown? clojure.lang.ExceptionInfo (client/matching-handle (dissoc identity :seon.boot/start-instant))))
        (is (client/matching-handle identity))
        (let [path (io/file root "data/clusters/default/prepl.edn") original (slurp path)
              endpoint (edn/read-string original)]
          (try
            (spit path (pr-str (assoc endpoint :seon.boot/start-instant (java.util.Date. 0))))
            (is (empty? (client/advertisements root)) "A positively reused PID advertisement is stale.")
            (spit path (pr-str (dissoc endpoint :seon.boot/start-instant)))
            (is (thrown? clojure.lang.ExceptionInfo (client/advertisements root))
                "Unknown identity must refuse, never masquerade as absent.")
            (finally (spit path original))))
        (evaluate! root "(do (locking seon.operator.runtime/running-instances (swap! seon.operator.runtime/running-instances assoc \"reserved-sibling\" :starting)) true)")
        (let [result (client/request! (assoc (request root :stop)
                                      :seon.boot/cluster-name "default" :seon.operator/force? true))]
          (is (:seon.error/at result) (pr-str result))
          (is (str/includes? (pr-str result) "reserved-sibling"))
          (is (client/matching-handle identity)))
        (evaluate! root "(do (locking seon.operator.runtime/running-instances (swap! seon.operator.runtime/running-instances dissoc \"reserved-sibling\")) true)")
        (let [signal (process/run-process! {:seon.operator.subprocess/argv ["kill" "-STOP" (str (:seon.boot/pid identity))]
                                            :seon.operator.subprocess/deadline-ms event-ms})]
          (is (zero? (:seon.operator.subprocess/exit signal))))
        (let [result (client/request! (assoc (request root :down) :seon.operator/force? true))]
          (is (= [identity] (:seon.operator/stopped-processes result)))
          (is (nil? (client/matching-handle identity))))))))

(deftest ^{:seon.test/long "Destructive replacement republishes the complete program from an empty store."
           :seon.test/long-ms 360000}
  reset-one-jvm
  (with-published-root!
    (fn [root children sentinel]
      (started! root children)
      (let [old (client/advertisement root nil)
            lock-file (io/file root "data/store.lock")
            inode (Files/getAttribute (.toPath lock-file) "unix:ino" (make-array LinkOption 0))
            marker (io/file root "data/store/b1b-marker")
            old-branch (:seon.store/branch
                         (client/request! (assoc (request root :init) :seon.boot/cluster-name "old-data")))]
          (is (= (registry/cluster-branch "old-data") old-branch))
          (is (true? (evaluate! root
                       (pr-str `(contains? (~'seon.cluster.registry/roster
                                             (:seon.store/store (get @~'seon.operator.runtime/running-instances "default")))
                                           ~old-branch)))))
        (spit marker "old data")
        (Files/createSymbolicLink (.toPath (io/file root "data/store/external-link"))
                                 (.toPath sentinel) (make-array java.nio.file.attribute.FileAttribute 0))
        (is (:seon.error/at (client/request! (request root :reset))))
        (is (.exists marker))
        (let [invalid-root (completed! (cli! (str root "/missing") children ["reset" "--force"]) event-ms)
              invalid-config (completed! (cli! root children ["reset" "--force" "--config" "missing.edn"]) event-ms)]
          (is (= 1 (:seon.probe/exit invalid-root)))
          (is (str/includes? (str (:seon.probe/err invalid-root)) "existing isolated operator-root"))
          (is (= 1 (:seon.probe/exit invalid-config)))
          (is (str/includes? (str (:seon.probe/err invalid-config)) "--config is supported only by start")))
        (is (client/matching-handle old))
        (is (= "old data" (slurp marker)))
        (let [result (completed! (cli! root children ["reset" "--force"]))
              current (client/advertisement root nil)]
          (is (zero? (:seon.probe/exit result)) (pr-str result))
          (is (not= (:seon.boot/pid old) (:seon.boot/pid current)))
          (is (nil? (client/matching-handle old)))
          (is (not (.exists marker)))
          (is (= "outside" (slurp sentinel)))
          (is (= inode (Files/getAttribute (.toPath lock-file) "unix:ino" (make-array LinkOption 0))))
          (is (= 2 (evaluate! root "(+ 1 1)")))
          (is (false? (evaluate! root
                        (pr-str `(contains? (~'seon.cluster.registry/roster
                                              (:seon.store/store (get @~'seon.operator.runtime/running-instances "default")))
                                            ~old-branch)))))
          (is (= #{(select-keys current [:seon.boot/pid :seon.boot/start-instant])}
                 (client/selected-processes root))))))))

(deftest ^{:seon.test/long "Winner retains real destructive acquisition through indexing; two real cold competitors refuse."
           :seon.test/long-ms 360000}
  start-during-reset
  (is (every? (instrument/instrumented) [#'boot/start! #'process/current-identity])
      "The in-process winner starts under the installed boot and process contracts.")
  (with-published-root!
    (fn [root children _]
      (let [deleting (promise) allow-delete (promise)
            serving (promise) allow-ready (promise)
            name (str "reset-" (id/id))
            delete fs/delete-recursively! serve cluster/serve!
            winner (atom nil)]
        (try
          (with-redefs [fs/delete-recursively!
                        (fn [& args]
                          (when (and (= (str root "/data/store") (first args))
                                     (not (realized? deleting)))
                            (deliver deleting true) (await! allow-delete :allow-delete cold-ms))
                          (apply delete args))
                        cluster/serve!
                        (fn [instance dials]
                          (when (= name (get-in instance [:seon.boot/config :seon.boot/cluster-name]))
                            (deliver serving true) (await! allow-ready :allow-ready cold-ms))
                          (serve instance dials))]
            (reset! winner (future (boot/start! {:seon.boot/root (str root "/data/clusters")
                                                :seon.boot/cluster-name name :seon.store/destroy? true})))
            (await! deleting :deletion-entry)
            (lock-refused! (cold-launch! root children false name))
            (is (not (realized? @winner)) "Winner remains paused until the real loser exits.")
            (deliver allow-delete true)
            (await! serving :before-ready cold-ms)
            (lock-refused! (cold-launch! root children false name))
            (is (not (realized? @winner)) "Winner remains paused until the real loser exits.")
            (deliver allow-ready true)
            (is (empty? (:seon.boot/missing-layers (boot/readiness (await! @winner :winner-ready cold-ms))))))
          (finally
            (deliver allow-delete true) (deliver allow-ready true)
            (when @winner (await! @winner :winner-cleanup cold-ms))))))))

(deftest ^{:seon.test/long "Captured old process exits before another owner wins the replacement gap."
           :seon.test/long-ms 240000}
  reset-loses-replacement-race
  (with-published-root!
    (fn [root children _]
      (started! root children)
      (let [captured (client/selected-processes root)]
        (is (:seon.operator/stopped?
             (client/request! (assoc (request root :stop)
                               :seon.boot/cluster-name "default" :seon.operator/force? true))))
        (is (every? #(nil? (client/matching-handle %)) captured)
            "Singleton force-stop proves actual captured process exit.")
        (started! root children)
        (let [winner (client/advertisement root nil)
              marker (io/file root "data/store/winner-marker")]
          (spit marker "winner")
          ;; Delayed graceful down must never select the replacement advertisement.
          (client/down! (request root :down) captured)
          (is (client/matching-handle winner))
          (let [_ (lock-refused! (cold-launch! root children true))]
            (is (= "winner" (slurp marker)))
            (is (client/matching-handle winner))
            (is (= 2 (evaluate! root "(+ 1 1)")))))))))

(deftest ^{:seon.test/long "Foreign-process exclusion before destructive deletion and after complete boot, then actual exit releases the retained inode."
           :seon.test/long-ms 360000}
  same-lock-through-reset-boot
  (with-published-root!
    (fn [root children _]
      (let [marker (io/file root "data/store/delete-entry-sentinel")
            lock-file (io/file root "data/store.lock")]
        (spit marker "untouched")
        (with-open [callback (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))]
          (.setSoTimeout callback (int event-ms))
          (let [launch (launch-process!
                        children ["clojure" (str "-J-Dseon.operator.root=" root)
                                  "-M:dev:test" "-m" "seon.cluster.boot-drill-child"
                                  root (str (.getLocalPort callback)) (str cold-ms) "destroy" "default"])]
            (with-open [socket (.accept callback)
                        reader (java.io.BufferedReader. (io/reader socket))
                        writer (io/writer socket)]
              (.setSoTimeout socket (int cold-ms))
              (let [identity (edn/read-string (.readLine reader))
                    _ (swap! children conj (select-keys identity [:seon.boot/pid :seon.boot/start-instant]))
                    _ (println "B1b controlled child" (pr-str identity))
                    entry (edn/read-string (.readLine reader))
                    inode (Files/getAttribute (.toPath lock-file) "unix:ino" (make-array LinkOption 0))]
                (is (= :delete-entry (:seon.probe/event entry)))
                (is (true? (:seon.probe/lock-valid? entry)))
                (lock-refused! (cold-launch! root children true))
                (is (= "untouched" (slurp marker)) "Foreign loser did not enter deletion.")
                (.write writer "continue\n") (.flush writer)
                (let [ready (edn/read-string (.readLine reader))]
                  (is (= :ready (:seon.probe/event ready)) (pr-str ready))
                  (is (true? (:seon.probe/same-lock? ready)))
                  (is (true? (:seon.probe/same-channel? ready)))
                  (is (empty? (get-in ready [:seon.boot/readiness :seon.boot/missing-layers]))))
                (spit marker "ready sentinel")
                (lock-refused! (cold-launch! root children true))
                (is (= "ready sentinel" (slurp marker)))
                (let [handle (client/matching-handle identity)]
                  (.destroyForcibly handle)
                  (.get (.onExit handle) event-ms TimeUnit/MILLISECONDS))
                (.get (.onExit (:seon.probe/process launch)) event-ms TimeUnit/MILLISECONDS)
                (await! (:seon.probe/out launch) :controlled-child-stdout)
                (await! (:seon.probe/err launch) :controlled-child-stderr)
                (is (= inode (Files/getAttribute (.toPath lock-file) "unix:ino" (make-array LinkOption 0))))
                (let [form `(with-open [channel# (java.nio.channels.FileChannel/open
                                                  (.toPath (clojure.java.io/file ~(str lock-file)))
                                                  (into-array java.nio.file.OpenOption
                                                              [java.nio.file.StandardOpenOption/WRITE]))]
                              (if-let [lock# (.tryLock channel#)]
                                (prn :acquired)
                                (prn :locked)))
                      acquired (completed! (launch-process! children ["bb" "-e" (pr-str form)]) event-ms)]
                  (is (zero? (:seon.probe/exit acquired)) (:seon.probe/err acquired))
                  (is (= :acquired (:seon.probe/value acquired))))))))))))
