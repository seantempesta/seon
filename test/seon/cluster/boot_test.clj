(ns seon.cluster.boot-test
  "Sealed acceptance for the entry rung (B0).

  Orchestrator-authored (2026-07-27). The implementation lane makes
  these green by implementing the seon.cluster stubs ONLY — schemas and
  tests are byte-sealed; friction is reported, never resolved by
  weakening. The lifecycle tests are LIVE: they open real prepl
  sockets in this JVM and prove the REPL answers — the falsifier, not a
  fixture. Filesystem fixtures live under the project-local tmp/
  (never a system temp dir)."
  (:require [clojure.edn :as edn]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.agent]
            [seon.cluster.source :as source]
            [seon.cluster.process :as cluster.process]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.flow :as seon.flow]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(defn- bare-root []
  (let [root (str "tmp/boot-test/" (random-uuid))]
    (.mkdirs (io/file root))
    root))

(def ^:dynamic *published-root* nil)

(defn- published-root
  []
  (when-not *published-root*
    (throw (ex-info "The published-root fixture is not installed." {})))
  (or @*published-root*
      (locking *published-root*
        (or @*published-root*
            (let [root (bare-root)]
              (cluster/refresh-source! root)
              (reset! *published-root* root)
              root)))))

(defn- fresh-root-with-history-policy [keep-history?]
  (let [root (bare-root)
        opened
        (store/open-store!
         {:seon.store/dir (str (io/file root "store"))
          :seon.config.db/keep-history? keep-history?})]
    (store/release-store! opened)
    (cluster/refresh-source! root)
    root))

(defn- delete-recursively! [path]
  (let [shared-root (some-> *published-root* deref io/file .getCanonicalPath)
        target (.getCanonicalPath (io/file path))]
    (when-not (= shared-root target)
      (test-support/delete-recursively! path))))

(defn- with-published-root
  [body]
  (binding [*published-root* (atom nil)]
    (try
      (body)
      (finally
        (when-let [root @*published-root*]
          (test-support/delete-recursively! root))))))

(use-fixtures :once with-published-root)

(defn- await-fact
  "Return the first truthy `probe` result published by a database value."
  [connection probe]
  (let [events (async/promise-chan)
        key (keyword (str (ns-name *ns*)) (str (gensym "fact-")))]
    (d/listen connection key
              (fn [report]
                (when-let [value (probe (:db-after report))]
                  (async/offer! events value))))
    (try
      (when-let [value (probe @connection)]
        (async/offer! events value))
      (test-support/await-event! events "database fact")
      (finally
        (d/unlisten connection key)))))

(defn- await-bootstrap!
  [connection agent-id]
  (await-fact
   connection
   (fn [db]
     (db/q '[:find ?closed-at .
            :in $ ?run-id
            :where
            [?run :seon.cluster.run/id ?run-id]
            [?run :seon.cluster.run/closed-at ?closed-at]]
          db (bootstrap/run-id agent-id)))))

(defn- write-source!
  [root relative-path source]
  (let [file (io/file root relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file source)
    (.getCanonicalPath file)))

(defn- prepl-eval
  "Open a real socket to `host:port`, evaluate `form-string` through
  io-prepl, return the :ret payload's :val string. The whole round trip
  is bounded by the socket timeout — a hang is a failure, not a wait."
  [host port form-string]
  (with-open [socket (java.net.Socket. ^String host (int port))]
    (.setSoTimeout socket 5000)
    (let [out (io/writer socket)
          in (io/reader socket)]
      (.write out (str form-string "\n"))
      (.flush out)
      (loop []
        (let [line (.readLine ^java.io.BufferedReader in)
              message (edn/read-string line)]
          (if (= :ret (:tag message))
            (:val message)
            (recur)))))))

(defn- registered-prepl-servers
  []
  (var-get (ns-resolve 'clojure.core.server 'servers)))

(defn- seed-incompatible-sovereign!
  [root cluster-name]
  (let [opened (store/open-store!
                {:seon.store/dir (str (io/file root "store"))})
        branch (registry/cluster-branch cluster-name)]
    (try
      (registry/branch! {:seon.store/store opened
                         :seon.cluster.registry/from :db
                         :seon.store/branch branch})
      (let [connection (store/open-branch! opened branch)
            forms (schema.edn/packaged-forms)
            declarations
            (mapv
             (fn [declaration]
               (if (= :seon.ns/requires (:db/ident declaration))
                 (assoc declaration :db/valueType :db.type/symbol)
                 declaration))
             (schema.datahike/malli->datahike-schema-in
              {:seon.schema.projection/forms forms}
              (schema/canonical-database-attributes forms)))]
        (try
          (db/transact! connection declarations)
          (db/transact!
           connection
           [{:seon.source/digest (apply str (repeat 64 "a"))}
            {:seon.ns/name 'legacy.core}
            {:seon.fn/sym "legacy.core/f"}])
          (finally
            (d/release connection))))
      (finally
        (store/release-store! opened)))))

(deftest ^{:seon.test/long "Starts a degraded real cluster against an incompatible store."}
  incompatible-sovereign-schema-refusal-steers-the-operator
  (let [root (bare-root)
        cluster-name "legacy"
        request {:seon.boot/cluster-name cluster-name
                 :seon.boot/root root}]
    (try
      (seed-incompatible-sovereign! root cluster-name)
      (let [failure (try
                      (cluster/start! request)
                      nil
                      (catch Exception error
                        error))
            causes (take-while some? (iterate ex-cause failure))
            mismatch
            (some
             (fn [cause]
               (let [offense (:seon.boot/offense (ex-data cause))]
                 (when (= :seon.ns/requires
                          (:seon.boot/attribute offense))
                   cause)))
             causes)
            outer-data (ex-data failure)
            mismatch-offense (:seon.boot/offense (ex-data mismatch))
            message (ex-message failure)]
        (try
          (testing "start! carries the degraded instance and cluster identity"
            (is (= :seon.boot/refused (:seon.error/kind outer-data)))
            (is (= cluster-name
                   (get-in outer-data
                           [:seon.boot/offense :seon.boot/cluster-name])))
            (is (some? (:seon.boot/cluster-connection
                        (:seon.boot/instance outer-data)))))
          (testing "the cause chain retains the exact schema mismatch"
            (is (some? mismatch))
            (is (= cluster-name
                   (:seon.boot/cluster-name mismatch-offense)))
            (is (= :db.type/symbol
                   (get-in mismatch-offense
                           [:seon.boot/installed :db/valueType])))
            (is (= :db.type/ref
                   (get-in mismatch-offense
                           [:seon.boot/current :db/valueType]))))
          (testing "the wrapped message names both operator resolutions"
            (is (str/includes? message
                               "predates the incompatible schema change"))
            (is (str/includes? message ":seon.ns/requires"))
            (is (str/includes? message
                               "bin/seon init legacy --force"))
            (is (str/includes? message "destroys and reforks"))
            (is (str/includes? message "export/import"))
            (is (str/includes? message "preserve")))
          (finally
            (some-> outer-data :seon.boot/instance cluster/stop!))))
      (finally
        (delete-recursively! root)))))

(deftest process-root-store-identity-is-canonical
  (let [root (bare-root)
        relative-store (str (io/file root "store"))
        absolute-store (.getCanonicalPath (io/file relative-store))
        acquire! (var-get (ns-resolve 'seon.cluster 'acquire-root-store!))
        release! (var-get (ns-resolve 'seon.cluster 'release-root-store!))]
    (try
      (let [relative (acquire! relative-store)
            absolute (acquire! absolute-store)]
        (try
          (is (identical? relative absolute)
              "relative and absolute paths share one process-root store")
          (release! relative-store)
          (is (= 1
                 (get-in @(var-get (ns-resolve 'seon.cluster
                                               'root-store-holder))
                         [absolute-store :seon.cluster/holders]))
              "the first alias release leaves the second holder counted")
          (finally
            (release! absolute-store))))
      (let [reopened (store/open-store! {:seon.store/dir absolute-store})]
        (try
          (is (store/connection? (:seon.store/connection reopened))
              "the final canonical holder releases the physical flock")
          (finally
            (store/release-store! reopened))))
      (finally
        (delete-recursively! root)))))

;;; ---------------------------------------------------------------------------
;;; Bootstrap resolution — generative over the whole override domain
;;; ---------------------------------------------------------------------------

(def ^:private name-gen
  (gen/fmap #(str "c" %) gen/nat))

(def ^:private overrides-gen
  "Any subset of valid override keys."
  (gen/let [name? gen/boolean
            root? gen/boolean
            port? gen/boolean
            cluster-name name-gen
            port (gen/choose 0 65535)]
    (cond-> {}
      name? (assoc :seon.boot/cluster-name cluster-name)
      root? (assoc :seon.boot/root (str "tmp/boot-test/gen-" cluster-name))
      port? (assoc :seon.boot/prepl-port port))))

(deftest bootstrap-resolution-is-total-over-overrides
  (let [check
        (tc/quick-check
         100
         (prop/for-all [overrides overrides-gen]
           (let [config (cluster/resolve-bootstrap overrides)]
             (and
              ;; complete and valid against the declared requirements
              (seon.schema/valid-candidate-value? :seon.boot/config config)
              ;; every supplied override wins verbatim
              (every? (fn [[k v]] (= v (get config k))) overrides)
              ;; defaults fill exactly the absent keys
              (= (get overrides :seon.boot/cluster-name "default")
                 (:seon.boot/cluster-name config))
              (= (get overrides :seon.boot/root "data/clusters")
                 (:seon.boot/root config))
              (string? (:seon.boot/log-dir config))
              ;; the process-root store every cluster branches from
              (string? (:seon.boot/store-dir config)))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "bootstrap resolution failed: " (pr-str check)))))

(deftest bootstrap-refuses-what-it-must
  (testing "an unused key accretes without changing the resolved config"
    (is (= :file
           (:seon.boot/store-backend
            (cluster/resolve-bootstrap {:seon.boot/store-backend :file})))))
  (testing "an invalid value is refused"
    (is (thrown? Exception
                 (cluster/resolve-bootstrap {:seon.boot/cluster-name ""})))
    (is (thrown? Exception
                 (cluster/resolve-bootstrap {:seon.boot/prepl-port 99999}))))
  (testing "no overrides means the complete defaults document"
    (let [config (cluster/resolve-bootstrap {})]
      (is (= "default" (:seon.boot/cluster-name config)))
      (is (= "data/clusters" (:seon.boot/root config)))
      (is (= "127.0.0.1" (:seon.boot/prepl-host config)))
      (is (= 0 (:seon.boot/prepl-port config))))))

(deftest paths-derive-from-root-and-name-alone
  (let [check
        (tc/quick-check
         100
         (prop/for-all [cluster-name name-gen]
           (let [root "tmp/boot-test/paths"
                 paths (cluster/cluster-paths root cluster-name)
                 dir (:seon.boot/cluster-dir paths)]
             (and (str/starts-with? dir root)
                  (str/includes? dir cluster-name)
                  (every? #(str/starts-with? % dir)
                          [(:seon.boot/advertisement-file paths)
                           (:seon.boot/log-dir paths)])
                  ;; the store is NOT here: it is per process root
                  ;; (branch-per-cluster, b2-plan section 0)
                  (not (contains? paths :seon.boot/store-dir))
                  (not= (:seon.boot/advertisement-file paths)
                        (:seon.boot/log-dir paths)))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "path derivation failed: " (pr-str check)))))

;;; ---------------------------------------------------------------------------
;;; Root executors — one pair per JVM, shared
;;; ---------------------------------------------------------------------------

(defn- observe-executor-step
  ([]
   {:ins {::observe-executor "A request to observe the proc workload thread."}
    :ping-map-fn (constantly {})})
  ([args]
   args)
  ([state _transition]
   state)
  ([{::keys [observations completed] :as state} _input workload]
   (swap! observations assoc workload (.isVirtual (Thread/currentThread)))
   (.countDown ^CountDownLatch completed)
   [state nil]))

(deftest root-executors-are-one-shared-pair
  (let [first-pair (cluster/root-executors)
        second-pair (cluster/root-executors)]
    (is (identical? (:compute first-pair) (:compute second-pair))
        "repeated calls return the SAME compute executor")
    (is (identical? (:io first-pair) (:io second-pair))
        "repeated calls return the SAME io executor")))

(deftest root-executor-workloads-run-on-the-required-thread-kinds
  (let [{:keys [compute io]} (cluster/root-executors)
        observations (atom {})
        completed (CountDownLatch. 2)
        proc-args {::observations observations ::completed completed}
        graph
        (flow/create-flow
         {:procs
          {:io {:proc (seon.flow/var-process
                       #'observe-executor-step :io proc-args)}
           :compute {:proc (seon.flow/var-process
                            #'observe-executor-step :compute proc-args)}}
          :conns []
          :io-exec io
          :compute-exec compute})]
    (try
      (flow/start graph)
      (flow/resume graph)
      (.get (flow/inject graph [:io ::observe-executor] [:io]))
      (.get (flow/inject graph [:compute ::observe-executor] [:compute]))
      (test-support/await-event! completed ::executor-workloads)
      (is (= {:io true :compute false} @observations)
          "blocking transport uses virtual threads; compute stays platform-bound")
      (finally
        (flow/stop graph)))))

;;; ---------------------------------------------------------------------------
;;; The live lifecycle — real sockets, real files, this JVM
;;; ---------------------------------------------------------------------------

(deftest ^{:seon.test/long "Starts a real cluster and proves its live prepl boundary."}
  repl-is-live-after-the-boot-tower
  (let [root (published-root)]
    (try
      (let [instance (cluster/start! {:seon.boot/cluster-name "solo"
                                      :seon.boot/root root})
            advertisement (:seon.boot/advertisement instance)
            answer (prepl-eval (:seon.boot/prepl-host advertisement)
                               (:seon.boot/prepl-port advertisement)
                               "(+ 20260727 1)")]
        (try
          (testing "the REPL answers with the evaluated value"
            (is (= "20260728" answer)))
          (testing "the completed tower records its measured duration"
            (is (nat-int? (:seon.boot/ready-ms instance))))
          (testing "the advertisement validates and is discoverable"
            (is (seon.schema/valid-candidate-value?
                 :seon.boot/advertisement advertisement))
            (is (= advertisement
                   (cluster/read-advertisement root "solo"))))
          (finally
            (cluster/stop! instance))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Starts two real clusters to prove process-root isolation."}
  two-instances-are-isolated
  (let [root (published-root)]
    (try
      (let [a (cluster/start! {:seon.boot/cluster-name "a"
                               :seon.boot/root root})
            b (cluster/start! {:seon.boot/cluster-name "b"
                               :seon.boot/root root})
            port-of #(get-in % [:seon.boot/advertisement
                                :seon.boot/prepl-port])]
        (try
          (testing "distinct coordinates, both answering by name"
            (is (not= (port-of a) (port-of b)))
            (is (= "\"a\"" (prepl-eval "127.0.0.1" (port-of a) "\"a\"")))
            (is (= "\"b\"" (prepl-eval "127.0.0.1" (port-of b) "\"b\""))))
          (testing "a second start! for a running cluster refuses"
            (is (thrown? Exception
                         (cluster/start! {:seon.boot/cluster-name "a"
                                          :seon.boot/root root}))))
          (testing "stopping a leaves b untouched"
            (cluster/stop! a)
            (is (nil? (cluster/read-advertisement root "a"))
                "a's advertisement is gone")
            (is (= "\"b\"" (prepl-eval "127.0.0.1" (port-of b) "\"b\"")))
            (is (some? (cluster/read-advertisement root "b"))))
          (testing "stop! is idempotent"
            (is (nil? (cluster/stop! a)))
            (is (nil? (cluster/stop! a))))
          (finally
            (cluster/stop! a)
            (cluster/stop! b))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Starts and stops a real cluster before probing advertisements."}
  stale-advertisements-read-as-absent
  (let [root (published-root)]
    (try
      (let [instance (cluster/start! {:seon.boot/cluster-name "stale"
                                      :seon.boot/root root})
            advertisement (:seon.boot/advertisement instance)
            file (io/file (:seon.boot/advertisement-file
                           (cluster/cluster-paths root "stale")))]
        (cluster/stop! instance)
        (testing "a wrong start-instant with a live pid reads as nil"
          (.mkdirs (.getParentFile file))
          (spit file
                (pr-str (assoc advertisement
                               :seon.boot/start-instant #inst "2000-01-01")))
          (is (nil? (cluster/read-advertisement root "stale"))))
        (testing "a dead pid reads as nil"
          (spit file (pr-str (assoc advertisement :seon.boot/pid 2)))
          (is (nil? (cluster/read-advertisement root "stale"))))
        (testing "garbage reads as nil, never as a throw"
          (spit file "{:not :an-advertisement")
          (is (nil? (cluster/read-advertisement root "stale")))))
      (finally
        (delete-recursively! root)))))

(deftest process-identity-refuses-a-recycled-pid-once-for-every-caller
  (let [identity (cluster.process/current-identity)]
    (is (cluster.process/live? identity))
    (is (false?
         (cluster.process/live?
          (update identity :seon.boot/start-instant
                  #(java.util.Date. (dec (inst-ms %))))))
        "the pid still exists, but a different generation is dead")))

(deftest ^{:seon.test/long "Restarts a real cluster generation to falsify stale teardown."}
  a-delayed-stop-never-kills-a-replacement
  ;; stops are instance-addressed: a stale stop! of an OLD instance
  ;; value must leave a same-named replacement fully alive
  (let [root (published-root)]
    (try
      (let [old-instance (cluster/start! {:seon.boot/cluster-name "swap"
                                          :seon.boot/root root})]
        (cluster/stop! old-instance)
        (let [replacement (cluster/start! {:seon.boot/cluster-name "swap"
                                           :seon.boot/root root})
              port (get-in replacement [:seon.boot/advertisement
                                        :seon.boot/prepl-port])]
          (try
            ;; the delayed second stop of the OLD value
            (is (nil? (cluster/stop! old-instance)))
            (is (= "\"alive\"" (prepl-eval "127.0.0.1" port "\"alive\""))
                "the replacement's REPL survived the stale stop")
            (is (some? (cluster/read-advertisement root "swap"))
                "the replacement's advertisement survived")
            (finally
              (cluster/stop! replacement)))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Restarts a real prepl server under one registered name."}
  same-jvm-same-name-restart-releases-the-registered-prepl
  (let [root (published-root)
        cluster-name "registered-restart"
        server-name (str "seon.cluster/" cluster-name)]
    (try
      (let [first-instance
            (cluster/start! {:seon.boot/cluster-name cluster-name
                             :seon.boot/root root})]
        (cluster/stop! first-instance)
        (is (not (contains? (registered-prepl-servers) server-name))
            "stop! releases the clojure.core.server name synchronously")
        (let [replacement
              (cluster/start! {:seon.boot/cluster-name cluster-name
                               :seon.boot/root root})]
          (try
            (is (contains? (registered-prepl-servers) server-name))
            (is (= "\"replacement\""
                   (prepl-eval
                    (get-in replacement
                            [:seon.boot/advertisement :seon.boot/prepl-host])
                    (get-in replacement
                            [:seon.boot/advertisement :seon.boot/prepl-port])
                    "\"replacement\"")))
            (finally
              (cluster/stop! replacement)))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Injects failure into real cluster teardown and retries it."}
  a-failed-stop-remains-addressable-and-retryable
  (let [root (published-root)
        cluster-name "retry-stop"
        original-release-store! store/release-store!]
    (try
      (let [instance (cluster/start! {:seon.boot/cluster-name cluster-name
                                      :seon.boot/root root})
            advertisement (:seon.boot/advertisement instance)
            registered-instances
            (var-get (ns-resolve 'seon.cluster 'running-instances))
            failure
            (with-redefs
              [store/release-store!
               (fn [_store]
                 (throw (ex-info "injected root-store release failure"
                                 {::injected true})))]
              (try
                (cluster/stop! instance)
                nil
                (catch Throwable failure
                  failure)))]
        (try
          (testing "a root-store release failure is loud"
            (is (instance? Throwable failure))
            (is (true? (::injected (ex-data failure)))))
          (testing "the failed generation remains exactly addressable"
            (is (identical? instance
                            (get @registered-instances cluster-name)))
            (is (= advertisement
                   (cluster/read-advertisement root cluster-name)))
            (is (= "\"addressable\""
                   (prepl-eval (:seon.boot/prepl-host advertisement)
                               (:seon.boot/prepl-port advertisement)
                               "\"addressable\""))))
          (testing "the failed generation excludes a replacement"
            (when (get @registered-instances cluster-name)
              (is (thrown? Exception
                           (cluster/start!
                            {:seon.boot/cluster-name cluster-name
                             :seon.boot/root root})))))
          (testing "a later stop retries the remaining release"
            (is (nil? (cluster/stop! instance)))
            (is (nil? (cluster/read-advertisement root cluster-name)))
            (is (nil? (get @registered-instances cluster-name))))
          (testing "the released name and flock admit a replacement"
            (when (nil? (cluster/read-advertisement root cluster-name))
              (let [replacement
                    (cluster/start! {:seon.boot/cluster-name cluster-name
                                     :seon.boot/root root})]
                (cluster/stop! replacement))))
          (finally
            ;; Keep a red test from leaking a live socket or flock into the
            ;; rest of the suite. Both releases are no-ops after a green retry.
            (cluster/stop! instance)
            (original-release-store! (:seon.store/store instance))
            (when-not (.isClosed
                       ^java.net.ServerSocket
                       (:seon.boot/prepl-server instance))
              (.close
               ^java.net.ServerSocket
               (:seon.boot/prepl-server instance))))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Stops a real cluster while one flow pass remains active."}
  orderly-stop-awaits-the-active-loop-pass
  (let [root (published-root)
        pass-entered (CountDownLatch. 1)
        finish-pass (CountDownLatch. 1)
        stop-commanded (CountDownLatch. 1)
        transaction-outcome (promise)
        original-transact! db/transact!
        original-stop flow/stop]
    (try
      (let [instance (cluster/start! {:seon.boot/cluster-name "stopping"
                                      :seon.boot/root root})
            connection (:seon.boot/cluster-connection instance)]
        (try
          (with-redefs
            [db/transact!
             (fn [conn tx-data]
               (.countDown pass-entered)
               (.await finish-pass)
               (let [outcome (original-transact! conn tx-data)]
                 (deliver transaction-outcome outcome)
                 outcome))
             work/next-agent-work
             (fn [_db _request]
               (db/transact! connection
                                [{:seon.cluster.agent/id "root"}])
               nil)
             flow/stop
             (fn [graph]
               (let [stopped? (original-stop graph)]
                 (.countDown stop-commanded)
                 stopped?))]
            ;; the pass under stop is ROOT'S TURN PROC now (F1): the
            ;; wake goes into root's own mailbox through the routing
            ;; entry, exactly where the listener would deliver it
            (async/offer! (:seon.cluster.wake/channel
                           (seon.cluster.agent/armed
                            (:seon.cluster.agent/routing instance)
                            "root"))
                          ::in-flight-transaction)
            (is (.await pass-entered 5 TimeUnit/SECONDS)
                "the loop pass reached its transaction boundary")
            (let [stopped (future (cluster/stop! instance))]
              (is (.await stop-commanded 5 TimeUnit/SECONDS)
                  "stop! sent Flow's stop command")
              (is (= ::still-stopping
                     (deref stopped 1000 ::still-stopping))
                  "orderly stop waits for the active pass")
              (.countDown finish-pass)
              (is (nil? (:seon.error/kind
                         (deref transaction-outcome 5000
                                {:seon.error/kind ::transaction-stuck})))
                  "the in-flight transaction commits before release")
              (is (nil? (deref stopped 5000 ::stop-stuck))
                  "stop! finishes after the pass publishes completion")))
          (finally
            (.countDown finish-pass)
            (cluster/stop! instance))))
      (finally
        (delete-recursively! root)))))

;;; ---------------------------------------------------------------------------
;;; The composed tower — store, source commit, fork, config, one start!
;;; ---------------------------------------------------------------------------

(defn- start-refusal
  [request]
  (try
    (cluster/start! request)
    nil
    (catch Throwable failure
      failure)))

(defn- stop-refused-instance!
  [failure]
  (some-> (ex-data failure) :seon.boot/instance cluster/stop!))

(deftest ^{:seon.test/long "Starts real clusters against a creation-fixed store policy."}
  operator-root-history-policy-is-creation-fixed
  (let [root (fresh-root-with-history-policy false)
        instance
        (cluster/start!
         {:seon.boot/root root
          :seon.boot/cluster-name "history-off"
          :seon.config/manifest {:seon.config.db/keep-history? false}})]
    (try
      (let [store (:seon.store/store instance)
            connection (:seon.boot/cluster-connection instance)]
        (is (false? (get-in @(:seon.store/connection store)
                            [:config :keep-history?])))
        (is (false? (get-in @connection [:config :keep-history?])))
        (is (false?
             (:seon.config.db/keep-history?
              (config/effective @connection "history-off"))))
        (testing "a sibling cannot request a different held representation"
          (let [failure
                (start-refusal
                 {:seon.boot/root root
                  :seon.boot/cluster-name "history-on-conflict"
                  :seon.config/manifest
                  {:seon.config.db/keep-history? true}})
                refusal (ex-cause failure)]
            (try
              (is (= :seon.cluster/keep-history-mismatch
                     (get-in (ex-data refusal)
                             [:seon.boot/offense :seon.boot/rule])))
              (is (= false
                     (get-in (ex-data refusal)
                             [:seon.boot/offense
                              :seon.store/keep-history?])))
              (is (= true
                     (get-in (ex-data refusal)
                             [:seon.boot/offense
                              :seon.config.db/keep-history?])))
              (finally
                (stop-refused-instance! failure))))))
      (finally
        (cluster/stop! instance)
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Restarts a real sovereign cluster from its older program facts."}
  start-allows-an-older-complete-program-without-indexing
  (let [root (published-root)
        cluster-name "stale-program"
        request {:seon.boot/cluster-name cluster-name
                 :seon.boot/root root}
        stale-digest (apply str (repeat 64 "f"))]
    (try
      (let [program-transactions-before
            (let [instance (cluster/start! request)
                  connection (:seon.boot/cluster-connection instance)
                  source-eid
                  (db/q '[:find ?source .
                         :where [?source :seon.source/digest _]]
                       @connection)]
              (await-bootstrap! connection "root")
              (db/transact!
               connection
               [[:db.fn/retractAttribute source-eid :seon.source/digest]
                {:db/id source-eid :seon.source/digest stale-digest}])
              (let [program-transactions
                    (db/q '[:find ?symbol ?tx
                           :where
                           [?function :seon.fn/sym ?symbol]
                           [?function :seon.fn/source _ ?tx]]
                         @connection)]
                (cluster/stop! instance)
                program-transactions))
            restarted (cluster/start! request)]
        (try
          (testing "an older complete corpus is a sovereign cluster world"
            (is (some? (:seon.cluster.agent/routing restarted)))
            (is (= stale-digest
                   (db/q '[:find ?digest .
                          :where [_ :seon.source/digest ?digest]]
                        @(:seon.boot/cluster-connection restarted)))))
          (testing "reopen never indexes or advances the recorded digest"
            (is (= program-transactions-before
                   (db/q '[:find ?symbol ?tx
                          :where
                          [?function :seon.fn/sym ?symbol]
                          [?function :seon.fn/source _ ?tx]]
                        @(:seon.boot/cluster-connection restarted)))))
          (finally
            (cluster/stop! restarted))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Starts a real cluster before and after corrupting program facts."}
  partial-clusters-refuse-and-fresh-clusters-are-current
  (let [root (published-root)
        cluster-name "partial-program"
        request {:seon.boot/cluster-name cluster-name
                 :seon.boot/root root}
        current-digest
        (:seon.source/digest (cluster/source-snapshot))]
    (try
      (let [instance (cluster/start! request)
            connection (:seon.boot/cluster-connection instance)]
        (await-bootstrap! connection "root")
        (testing "a fresh fork is born at the current source digest"
          (is (= current-digest
                 (db/q '[:find ?digest .
                        :where [_ :seon.source/digest ?digest]]
                      @connection)))
          (is (pos? (db/q '[:find (count ?function) .
                           :where [?function :seon.fn/sym]]
                         @connection))))
        (db/transact!
         connection
         (mapv (fn [eid] [:db.fn/retractEntity eid])
               (db/q '[:find [?function ...]
                      :where [?function :seon.fn/sym]]
                    @connection)))
        (cluster/stop! instance))
      (let [failure (start-refusal request)
            refused-instance (:seon.boot/instance (ex-data failure))
            connection (:seon.boot/cluster-connection refused-instance)]
        (try
          (testing "namespaces without functions are denied despite a current digest"
            (is (str/includes? (ex-message failure) "partial"))
            (is (str/includes? (ex-message failure)
                               (str "bin/seon init " cluster-name " --force")))
            (is (pos? (db/q '[:find (count ?namespace) .
                             :where [?namespace :seon.ns/name]]
                           @connection)))
            (is (zero? (or
                        (db/q '[:find (count ?function) .
                               :where [?function :seon.fn/sym]]
                             @connection)
                        0))))
          (finally
            (stop-refused-instance! failure))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Keeps a real cluster live across source publication and a later fork."}
  incremental-source-refresh-publishes-without-touching-existing-clusters
  (let [root (published-root)
        current-digest
        (:seon.source/digest (cluster/source-snapshot))
        old-world
        (cluster/start!
         {:seon.boot/cluster-name "old-world"
          :seon.boot/root root})]
    (try
      (let [old-connection (:seon.boot/cluster-connection old-world)
            _ (await-bootstrap! old-connection "root")
            old-basis (:max-tx @old-connection)
            refreshed (cluster/refresh-source!
                       root ["src/seon/ai/tokens.cljc"])
            artifact
            (edn/read-string (slurp (cluster/source-artifact-file root)))
            roster
            (registry/roster (:seon.store/store old-world))]
        (testing "the one published source branch advances from its artifact"
          (is (= source/current-branch (:seon.source/branch refreshed)))
          (is (= current-digest (:seon.source/digest refreshed)))
          (is (true? (:seon.source/built? refreshed)))
          (is (uuid? (:seon.source/commit-id refreshed)))
          (is (= (:seon.source/commit-id refreshed)
                 (:seon.source/commit-id artifact)))
          (is (seq (:seon.source/file-digests artifact)))
          (is (= current-digest (:seon.source/digest artifact)))
          (is (seq (get-in artifact
                           [:seon.fn/manifest
                            :seon.fn.manifest/artifacts])))
          (is (contains? roster source/current-branch))
          (is (= 1 (count (filter #{source/current-branch} roster)))))
        (testing "the existing cluster remains on its independent commit"
          (is (= old-basis (:max-tx @old-connection)))
          (is (= current-digest
                 (db/q '[:find ?digest .
                        :where [_ :seon.source/digest ?digest]]
                      @old-connection))))
        (testing "future clusters fork the published commit"
          (let [future (cluster/start!
                        {:seon.boot/cluster-name "future-world"
                         :seon.boot/root root})]
            (try
              (is (= current-digest
                     (db/q '[:find ?digest .
                            :where [_ :seon.source/digest ?digest]]
                          @(:seon.boot/cluster-connection future))))
              (finally
                (cluster/stop! future))))))
      (finally
        (cluster/stop! old-world)
        (delete-recursively! root)))))

(deftest packaged-source-does-not-capture-an-ambient-schema-registration
  (let [dial :seon.config.boot-test/reopen-dial
        schema-state (schema/snapshot-state)]
    (try
      ;; Process-global registration is a REPL/runtime concern. It is not a
      ;; packaged source edit and therefore cannot silently mutate current-src.
      (schema/register!
       dial
       [:int {:seon.db/index true :seon.config/default 7}])
      (is (= [:int {:seon.db/index true :seon.config/default 7}]
             (get-in (schema/snapshot-state)
                     [:seon.schema.state/candidate-forms dial])))
      (is (not (contains? (schema.edn/packaged-forms) dial)))
      (finally
        (schema/restore-state! schema-state)))))

(deftest ^{:seon.test/long "Restarts a real cluster to prove configuration applies before arming."}
  selected-config-repairs-locked-state-before-consumers-arm
  (let [root (published-root)
        cluster-name "config-unlock"
        observed (atom [])
        start-work-launcher! seon.flow/start-work-launcher!
        arm-agents! (var-get (ns-resolve 'seon.cluster 'arm-agents!))]
    (try
      (let [instance (cluster/start! {:seon.boot/cluster-name cluster-name
                                      :seon.boot/root root})
            connection (:seon.boot/cluster-connection instance)]
        (db/transact!
         connection
         {:tx-data
          [{:seon.config/cluster cluster-name
            :seon.config.flow.compute/queue-depth 1}]})
        (cluster/stop! instance))
      (with-redefs-fn
        {#'seon.flow/start-work-launcher!
         (fn [request]
           (swap! observed conj
                  [:launcher
                   (:seon.config.flow.compute/queue-depth
                    (::seon.flow/configuration request))])
           (start-work-launcher! request))
         (ns-resolve 'seon.cluster 'arm-agents!)
         (fn [instance connection name]
           (swap! observed conj
                  [:agents
                   (:seon.config.flow.compute/queue-depth
                    (config/effective @connection name))])
           (arm-agents! instance connection name))}
        #(let [instance
               (cluster/start!
                {:seon.boot/cluster-name cluster-name
                 :seon.boot/root root
                 :seon.config/manifest
                 {:seon.config.flow.compute/queue-depth 37}})]
           (try
             (is (= [[:launcher 37] [:agents 37]] @observed)
                 "selected config settles before launcher install and graph arm")
             (finally
               (cluster/stop! instance)))))
      (finally
        (delete-recursively! root)))))

(deftest incremental-source-refresh-requires-every-unreported-file-to-match
  (let [current? (deref #'cluster/unreported-source-current?)
        published {"/repo/src/a.clj" "a1"
                   "/repo/src/b.clj" "b1"
                   "/repo/resources/schema.edn" "s1"}]
    (is (current? published
                  (assoc published "/repo/src/a.clj" "a2")
                  ["/repo/src/a.clj"]))
    (is (false? (current? published
                          (assoc published "/repo/src/b.clj" "b2")
                          ["/repo/src/a.clj"])))
    (is (false? (current? published
                          (dissoc published "/repo/resources/schema.edn")
                          ["/repo/src/a.clj"])))
    (is (false? (current? published
                          (assoc published "/repo/src/new.clj" "n1")
                          ["/repo/src/a.clj"])))))

(deftest current-source-digest-names-the-merged-schema-declarations
  (let [schema-path (.getCanonicalPath (io/file "resources/seon/schemas"))]
    (is (= (schema.edn/declaration-digest)
           (get (:seon.source/file-digests (cluster/source-snapshot))
                schema-path))
        "the ancestor hashes the merged schema declaration set"))
  (is (some #{"resources/seon/bootstrap.edn"} cluster/source-roots)
      "the ancestor hashes the bootstrap forms installed as source facts")
  (is (not-any? #{"resources" "resources/seon/schemas"} cluster/source-roots)
      "schema directory organization is not part of the ancestor digest"))

(deftest ^{:seon.test/long
           "Publishes real source edits to cover complete fallback and incremental branch agreement."}
  incremental-source-refresh-preserves-agreement-across-real-edits
  (let [root (bare-root)
        project (io/file root "project")
        source-root (io/file project "src")
        test-root (io/file project "test")
        resource-root (io/file project "resources")
        schema-path (write-source! resource-root "seon/schema.edn" "{}\n")
        a-path (write-source! source-root "sample/a.clj"
                              "(ns sample.a)\n(defn value [] 1)\n")
        b-path (write-source! source-root "sample/b.clj"
                              "(ns sample.b)\n(defn value [] 10)\n")
        roots (mapv #(.getCanonicalPath ^java.io.File %)
                    [source-root test-root])
        all-roots (conj roots schema-path)]
    (.mkdirs test-root)
    (try
      (with-redefs [seon.fn/source-roots roots
                    cluster/source-roots all-roots]
        (cluster/refresh-source! root)

        (testing "two consecutive scalar edits retain complete artifacts"
          (write-source! source-root "sample/a.clj"
                         "(ns sample.a)\n(defn value [] 2)\n")
          (cluster/refresh-source! root [a-path])
          (write-source! source-root "sample/a.clj"
                         "(ns sample.a)\n(defn value [] 3)\n")
          (cluster/refresh-source! root [a-path])
          (let [artifact
                (edn/read-string (slurp (cluster/source-artifact-file root)))
                file-artifact
                (seon.fn/artifact-by-path (:seon.fn/manifest artifact) a-path)]
            (is (= (:seon.fn.file/identities file-artifact)
                   (->> (:seon.fn.file/rows file-artifact)
                        (keep program/row-identity)
                        (sort-by pr-str)
                        vec))
                "the artifact remains a complete file projection")))

        (testing "a missed X followed by reported Y forces complete repair"
          (write-source! source-root "sample/a.clj"
                         "(ns sample.a)\n(defn value [] 4)\n")
          (write-source! source-root "sample/b.clj"
                         "(ns sample.b)\n(defn value [] 20)\n")
          (cluster/refresh-source! root [b-path])
          (let [opened (store/open-store!
                        {:seon.store/dir (str (io/file root "store"))})]
            (try
              (let [db (d/branch-as-db (:seon.store/connection opened)
                                       source/current-branch)]
                (is (str/includes?
                     (db/q '[:find ?source .
                            :where
                            [?function :seon.fn/sym "sample.a/value"]
                            [?function :seon.fn/source ?source]]
                          db)
                     "[] 4"))
                (is (str/includes?
                     (db/q '[:find ?source .
                            :where
                            [?function :seon.fn/sym "sample.b/value"]
                            [?function :seon.fn/source ?source]]
                          db)
                     "[] 20")))
              (finally
                (store/release-store! opened))))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Starts the complete real boot tower and a sibling cluster."}
  the-tower-stands-in-one-start
  (let [root (published-root)]
    (try
      (let [started-at (System/nanoTime)
            phases (atom [])
            progress-var (ns-resolve 'seon.cluster '*boot-progress!*)
            instance
            (with-bindings
              {progress-var #(swap! phases conj %)}
              (cluster/start! {:seon.boot/cluster-name "tower"
                               :seon.boot/root root
                               :seon.config/manifest
                               {:seon.config.flow.compute/queue-depth 11}}))
            elapsed-ms (/ (- (System/nanoTime) started-at) 1e6)]
        (try
          (testing "every tower field is present — nothing degraded"
            (is (some? (:seon.store/store instance)))
            (is (store/connection?
                 (:seon.boot/cluster-connection instance)))
            (is (map? (:seon.boot/config-result instance))))
          (testing "the whole tower reports its measured duration"
            (is (nat-int? (:seon.boot/ready-ms instance)))
            (is (<= (:seon.boot/ready-ms instance) elapsed-ms)))
          (testing "each published tower boundary reports one phase"
            (is (= [:seon.boot.phase/repl
                    :seon.boot.phase/store
                    :seon.boot.phase/branch
                    :seon.boot.phase/recovery
                    :seon.boot.phase/config
                    :seon.boot.phase/program
                    :seon.boot.phase/work-launcher
                    :seon.boot.phase/agents
                    :seon.boot.phase/web
                    :seon.boot.phase/ready]
                   @phases)))
          (testing "a second cluster in the same process forks
                    near-instantly off the shared store"
            (let [forked-at (System/nanoTime)
                  sibling (cluster/start! {:seon.boot/cluster-name "twr2"
                                           :seon.boot/root root
                                           :seon.config/manifest
                                           {:seon.config.flow.compute/queue-depth
                                            22}})
                  fork-ms (/ (- (System/nanoTime) forked-at) 1e6)]
              (try
                (is (some? (:seon.boot/cluster-connection sibling)))
                (is (identical? (:seon.store/store instance)
                                (:seon.store/store sibling))
                    "siblings share the ONE process-root store")
                (is (= 11
                       (:seon.config.flow.compute/queue-depth
                        (config/effective
                         @(:seon.boot/cluster-connection instance)
                         "tower"))))
                (is (= 22
                       (:seon.config.flow.compute/queue-depth
                        (config/effective
                         @(:seon.boot/cluster-connection sibling)
                         "twr2")))
                    "two clusters in one JVM retain distinct applied configs")
                (is (< fork-ms 2000)
                    (str "sibling fork took " fork-ms " ms"))
                (finally
                  (cluster/stop! sibling)))))
          (finally
            (cluster/stop! instance))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Starts a real prepl over deliberately corrupted boot storage."}
  a-failed-tower-never-takes-the-repl
  ;; owner ruling: the REPL is always useful for debugging — a corrupt
  ;; store fails the boot LOUDLY while the socket stays up
  (let [root (bare-root)]
    (try
      ;; a FILE where the store directory belongs corrupts layer 1
      (spit (io/file root "store") "not a store")
      (let [degraded
            (try
              (cluster/start! {:seon.boot/cluster-name "wreck"
                               :seon.boot/root root})
              (is false "the failed boot must throw")
              nil
              (catch Exception e
                (:seon.boot/instance (ex-data e))))]
        (is (map? degraded)
            "the throw carries the degraded instance")
        (is (nil? (:seon.store/store degraded))
            "the tower fields are absent from the failure point")
        (let [advertisement (cluster/read-advertisement root "wreck")]
          (is (some? advertisement)
              "the advertisement survived the failure")
          (is (= "\"alive\""
                 (prepl-eval (:seon.boot/prepl-host advertisement)
                             (:seon.boot/prepl-port advertisement)
                             "\"alive\""))
              "the REPL answers over the wreckage"))
        (testing "the carried instance stops like any other"
          (is (nil? (cluster/stop! degraded)))
          (is (nil? (cluster/read-advertisement root "wreck")))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Starts and reforks a real cluster branch."}
  explicit-refork-destroys-the-old-branch-and-forks-current-source
  (let [root (published-root)
        cluster-name "refork-program"
        instance (cluster/start! {:seon.boot/cluster-name cluster-name
                                  :seon.boot/root root})
        connection (:seon.boot/cluster-connection instance)]
    (try
      (db/transact! connection
                  [{:seon.cluster.message/id "history-refork-destroys"}])
      (let [result (cluster/refork! instance)
            replacement
            (cluster/start! {:seon.boot/cluster-name cluster-name
                             :seon.boot/root root})]
        (try
          (testing "the old branch was replaced from current-src"
            (is (:seon.cluster/created? result))
            (is (nil?
                 (db/q '[:find ?message .
                        :where
                        [?message :seon.cluster.message/id
                         "history-refork-destroys"]]
                      @(:seon.boot/cluster-connection replacement))))
            (is (pos?
                 (or
                  (db/q '[:find (count ?function) .
                         :where [?function :seon.fn/sym]]
                       @(:seon.boot/cluster-connection replacement))
                  0))))
          (finally
            (cluster/stop! replacement))))
      (finally
        ;; `refork!` normally stopped it. This is deliberately idempotent
        ;; cleanup for a failure before that boundary.
        (cluster/stop! instance)
        (delete-recursively! root)))))

;;; ---------------------------------------------------------------------------
;;; Boot recovery — a dead holder's wreckage is settled before anything resumes
;;; ---------------------------------------------------------------------------

(deftest ^{:seon.test/long "Restarts a real cluster over simulated process wreckage."}
  a-dead-holders-run-is-unclaimed-by-the-time-start-returns
  ;; The live crash drill found this gap: `recover-tx` existed with no
  ;; caller, so a process that died holding a claimed run left the agent
  ;; wedged — `work/next-agent-work` sees a run held by someone else and
  ;; returns nothing, forever. Recovery is BY FACT: the drill measured
  ;; the dead holder's 60-second lease still in the future when the fix
  ;; fires, so nothing here waits a lease out.
  (let [root (published-root)]
    (try
      ;; a first boot writes the wreckage a kill -9 mid-model-call leaves:
      ;; an open run claimed by a process that will not exist afterwards,
      ;; a live lease, and a dangling :running receipt
      (let [instance (cluster/start! {:seon.boot/cluster-name "recov"
                                      :seon.boot/root root})
            connection (:seon.boot/cluster-connection instance)
            now (java.util.Date.)]
        (await-bootstrap! connection "root")
        (db/transact! connection [{:seon.cluster.agent/id "alice"}])
        (db/transact! connection
                    [{:seon.cluster.run/id "run-crashed"
                      :seon.cluster.run/agent [:seon.cluster.agent/id "alice"]
                      :seon.cluster.run/opened-at now
                      :seon.cluster.run/process "99999-1"
                      :seon.cluster.run/plan-digest (apply str (repeat 64 "a"))}
                     {:seon.cluster.agent/id "alice"
                      :seon.cluster.agent/run [:seon.cluster.run/id "run-crashed"]}
                     {:seon.cluster.run.form/id "f-0"
                      :seon.cluster.run.form/run [:seon.cluster.run/id "run-crashed"]
                      :seon.cluster.run.form/ordinal 0
                      :seon.cluster.run.form/source "(+ 1 1)"}
                     ;; dangling = started with no terminal fact —
                     ;; running IS that absence, there is no status
                     {:seon.cluster.eval/id "e-0"
                      :seon.cluster.eval/run [:seon.cluster.run/id "run-crashed"]
                      :seon.cluster.eval/ordinal 0
                      :seon.cluster.eval/at now}])
        (cluster/stop! instance))

      ;; the next boot must settle it, with no lease wait
      (let [instance (cluster/start! {:seon.boot/cluster-name "recov"
                                      :seon.boot/root root})
            connection (:seon.boot/cluster-connection instance)]
        (try
          (testing "the dead holder's custody is gone — custody is
                    presence, and no run holds any"
            (is (nil? (db/q (quote [:find ?p . :where
                                   [_ :seon.cluster.run/process ?p]])
                           @connection))))
          (testing "its dangling receipt carries interrupted-at — the
                    one terminal fact recovery asserts"
            (is (= 1 (count
                      (db/q (quote [:find [?at ...] :where
                                   [_ :seon.cluster.eval/interrupted-at ?at]])
                           @connection)))))
          (testing "and the run is CLOSED with its plan intact —
                    recovery ends custody and no plan form can execute"
            (is (some? (db/q (quote [:find ?c . :where
                                    [_ :seon.cluster.run/closed-at ?c]])
                            @connection)))
            (is (some? (db/q (quote [:find ?d . :where
                                    [_ :seon.cluster.run/plan-digest ?d]])
                            @connection))))
          (testing "and the instance reports what recovery did"
            (is (= 1 (:seon.boot/recovered-runs instance)))
            (is (pos? (:seon.boot/recovery-operations instance))))
          (finally
            (cluster/stop! instance))))

      ;; a clean boot commits nothing
      (let [instance (cluster/start! {:seon.boot/cluster-name "clean"
                                      :seon.boot/root root})]
        (try
          (is (= 0 (:seon.boot/recovery-operations instance))
              "a store with no wreckage is not written to at boot")
          (finally
            (cluster/stop! instance))))
      (finally
        (delete-recursively! root)))))

;;; ---------------------------------------------------------------------------
;;; What boot's seeding leaves derivable
;;;
;;; A LIVE BOOT, never a fixture, because that is exactly the gap this
;;; invariant fell through: every in-memory fixture agreed while a booted
;;; cluster served a root page with one of its four blocks and no sign the
;;; other three were missing. The cause was Datahike's fused
;;; `:sorted-merge` path taking a cardinality-many scan (fixed in the
;;; vendored fork, `datahike.query.plan/build-pipeline`); the class is
;;; "a derivation disagrees with the facts it derives from", so the
;;; assertion is that equality, at the choke point, against a real store.
;;; ---------------------------------------------------------------------------
