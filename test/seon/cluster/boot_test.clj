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
            [seon.turn :as turn]
            [seon.cluster.store :as store]

            [seon.config :as config]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.fn.analyzer :as analyzer]
            [seon.flow :as seon.flow]
            [seon.render.route :as route]
            [seon.fs :as fs]
            [seon.operator :as operator]
            [seon.program :as program]
            [seon.render.transcript :as transcript]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support])
  (:import [java.io File]
           [java.util.concurrent CompletableFuture CountDownLatch TimeUnit]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (test-support/environment "seon.cluster.boot-test")))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(defn- bare-root []
  (let [root (str "tmp/boot-test/" (random-uuid))]
    (.mkdirs (io/file root))
    root))

(defn- derived-store-dir
  [root]
  (:seon.boot/store-dir
   (cluster/resolve-bootstrap {:seon.boot/root (str root)})))

(def ^:dynamic *published-root* nil)

(defn- published-root
  []
  (when-not *published-root*
    (throw (ex-info "The published-root fixture is not installed." {})))
  (or @*published-root*
      (locking *published-root*
        (or @*published-root*
            (let [root (bare-root)]
              (test-support/populate-published-root! root)
              (reset! *published-root* root)
              root)))))

(defn- fresh-root-with-history-policy [keep-history?]
  (let [root (bare-root)
        opened
        (store/open-store!
         {:seon.store/dir (derived-store-dir root)
          :seon.config.db/keep-history? keep-history?})]
    (store/release-store! opened)
    (cluster/refresh-source! root)
    root))

(defn- delete-recursively! [path]
  (let [shared-root (some-> *published-root* deref io/file .getCanonicalPath)
        target (.getCanonicalPath (io/file path))]
    (when-not (= shared-root target)
      (test-support/delete-recursively! path))))

(defn- delete-process-store!
  [root]
  (let [store-dir (io/file (derived-store-dir root))
        authority (or (System/getProperty "seon.operator.root")
                      (.getCanonicalPath (io/file root)))]
    (doseq [path [store-dir
                  (io/file (store/lock-file (.getPath store-dir)))
                  (io/file (.getParentFile store-dir) "blob-staging")]]
      (when (.exists path)
        (fs/delete-recursively! authority (.getPath path))))))

(defn- with-published-root
  [body]
  (let [process-root (bare-root)]
    (binding [*published-root* (atom nil)]
      (try
        (delete-process-store! process-root)
        (test-support/preserving-instrumentation-state body)
        (finally
          (when-let [root @*published-root*]
            (delete-process-store! root)
            (test-support/delete-recursively! root))
          (when (.exists (io/file process-root))
            (test-support/delete-recursively! process-root)))))))

(use-fixtures :each with-published-root)

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
            [?run :seon.turn/id ?run-id]
            [?run :seon.turn/closed-tx ?closed-at]]
          db (bootstrap/run-id agent-id)))))

(defn- write-source!
  [root relative-path source]
  (let [file (io/file root relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file source)
    (.getCanonicalPath file)))

(defn- activation-missing-member?
  [missing]
  (or (contains? missing :seon.activation/executable-symbol)
      (and (contains? missing :seon.activation/lookup-attribute)
           (contains? missing :seon.activation/lookup-value))))

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
                {:seon.store/dir (derived-store-dir root)})
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
          (test-support/transacted! connection declarations)
          (let [source-digest (apply str (repeat 64 "a"))]
            (test-support/transacted!
                         connection
                         [{:seon.source/digest source-digest
                           ;; Even this deliberately old sovereign source represents a
                           ;; publication. Its stored activation closure is complete for
                           ;; the sparse legacy facts; boot may then reach the intended
                           ;; incompatible-schema refusal instead of correctly refusing
                           ;; an unsealed source first.
                           :seon.source/activation-closure
                           {:seon.activation/source-digest source-digest
                            :seon.activation/schema-keys #{}
                            :seon.activation/required-attributes #{}
                            :seon.activation/config-defaults #{}
                            :seon.activation/config-required #{}
                            :seon.activation/executable-symbols #{"legacy.core/f"}
                            :seon.activation/lookup-refs []}}
            {:seon.ns/name 'legacy.core}
                          {:seon.fn/sym "legacy.core/f"
                           :seon.schema.admission/source :core
                           :seon.fn/ns [:seon.ns/name 'legacy.core]}]))
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
            ;; The steer no longer says "predates": `0b910eb69` replaced that
            ;; wording with the property and BOTH of its values, because the
            ;; refusal now derives from Datahike's own acceptance rule per
            ;; facet instead of whole-map inequality. The expectation here was
            ;; left behind by that commit; the current words are the ruled
            ;; ones.
            (is (str/includes? message "cannot reopen in place"))
            (is (str/includes? message ":db/valueType"))
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
          (is (store/connection? (:seon.store/connection-object reopened))
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
              (seon.schema/valid-candidate-value? (seon.schema/handed-projection) :seon.boot/config config)
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
      (is (= (.getCanonicalPath (io/file "data" "store"))
             (:seon.boot/store-dir config)))
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
        proc-args {:seon.env/environment @test-environment
                   ::observations observations
                   ::completed completed}
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

(deftest ^{:seon.test/fixture-observation "The test connects to the actual advertised REPL after the complete published-root boot."} ^{:seon.test/long
           "47.230 s pool: published-base clone, real ordered boot, live prepl call, and stop."}
  repl-is-live-after-ordered-boot
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
          (testing "the completed boot records its measured duration"
            (is (nat-int? (:seon.boot/ready-ms instance))))
          (testing "the READY fork owns the complete published activation"
            (is (= (:seon.source/digest (cluster/source-snapshot))
                   (:seon.activation/source-digest
                    (cluster/require-activation!
                     @(:seon.boot/cluster-connection instance))))))
          (testing "the advertisement validates and is discoverable"
            (is (seon.schema/valid-candidate-value? (seon.schema/handed-projection)
                 :seon.boot/advertisement advertisement))
            (is (= advertisement
                   (cluster/read-advertisement root "solo"))))
          (finally
            (cluster/stop! instance))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/fixture-observation "The assertions observe independently addressable booted REPLs and advertisements and isolated shutdown."} ^{:seon.test/long "Starts two real clusters to prove process-root isolation."}
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

(deftest ^{:seon.test/fixture-observation "The test mutates a real boot advertisement and checks process identity validation against the live root."} ^{:seon.test/long "Starts and stops a real cluster before probing advertisements."}
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

(deftest ^{:seon.test/fixture-observation "The assertion observes real replacement REPL and advertisement survival after stopping an older boot generation."} ^{:seon.test/long "Restarts a real cluster generation to falsify stale teardown."}
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

(deftest ^{:seon.test/fixture-observation "The test observes real prepl registration release and same-name replacement after physical-root shutdown."} ^{:seon.test/long
           "49.939 s pool: real start/stop/start generation proves registered prepl release."}
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

(deftest ^{:seon.test/fixture-observation "The assertion observes lifetime store-lock ownership and retryable release across failed shutdown and replacement boot."} ^{:seon.test/long "Injects failure into real cluster teardown and retries it."}
  a-failed-stop-remains-addressable-and-retryable
  (let [root (published-root)
        cluster-name "retry-stop"
        original-release-store! store/release-store!
        root-store-key (derived-store-dir root)
        retry-release-calls (atom 0)]
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
            (with-redefs
              [store/release-store!
               (fn [held-store]
                 (swap! retry-release-calls inc)
                 (original-release-store! held-store))]
              (is (nil? (cluster/stop! instance))))
            (is (= 1 @retry-release-calls)
                "the retry reaches the root-store release that failed")
            (is (nil? (get @(var-get (ns-resolve 'seon.cluster
                                                 'root-store-holder))
                           root-store-key))
                "the retry releases the final process-root store holder")
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
            (try
              (cluster/stop! instance)
              (finally
                (try
                  (original-release-store! (:seon.store/store instance))
                  (finally
                    (when-not (.isClosed
                               ^java.net.ServerSocket
                               (:seon.boot/prepl-server instance))
                      (.close
                       ^java.net.ServerSocket
                       (:seon.boot/prepl-server instance))))))))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/fixture-observation "The test observes an active booted loop transaction completing before shutdown releases the physical store."} ^{:seon.test/long "Stops a real cluster while one flow pass remains active."}
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
             turn/next-agent-work
             (fn [_db _request]
               (test-support/transacted! connection
                                            [{:seon.agent/id "root"}])
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
                            (:seon.agent/routing instance)
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
;;; Ordered boot — store, source commit, fork, config, one start!
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

(deftest ^{:seon.test/long
           "133.791 s pool: fresh physical-store creation plus real starts/reopens under both history policies."}
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
        (is (false? (get-in @(:seon.store/connection-object store)
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

(deftest ^{:seon.test/fixture-observation "The subject is reopening a sovereign older physical-store branch without republishing its program."} ^{:seon.test/long "Restarts a real sovereign cluster from its older program facts."}
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
              (test-support/transacted!
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
            (is (some? (:seon.agent/routing restarted)))
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

(deftest ^{:seon.test/fixture-observation "The assertions exercise real boot admission of incomplete versus freshly published cluster branches."} ^{:seon.test/long
           "114.810 s pool: real boot, program-fact corruption/refusal, and fresh-cluster currentness proof."}
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
        (test-support/transacted!
                     connection
                     (mapv (fn [eid] [:db.fn/retractEntity eid])
                           (db/q '[:find [?function ...]
                                  :where [?function :seon.fn/sym]]
                                @connection)))
        (cluster/stop! instance))
      (let [failure (start-refusal request)
            refused-instance (:seon.boot/instance (ex-data failure))
            connection (:seon.boot/cluster-connection refused-instance)
            activation-refusal
            (some (fn [cause]
                    (let [offense (:seon.boot/offense (ex-data cause))]
                      (when (seq (:seon.activation/missing offense))
                        offense)))
                  (take-while some? (iterate ex-cause failure)))]
        (try
          (testing "namespaces without functions are denied despite a current digest"
            (is (some? activation-refusal))
            (is (every? activation-missing-member?
                        (:seon.activation/missing activation-refusal)))
            (is (pos? (:seon.activation/missing-count activation-refusal)))
            (is (< (count (ex-message failure)) 2000))
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

(deftest ^{:seon.test/fixture-observation "The assertions compare real publication commit heads with existing and newly forked physical-store cluster branches."} ^{:seon.test/long
           "186.733 s pool: complete incremental publication dominates, followed by existing-cluster and later-fork agreement."}
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
            ;; Bootstrap closure leaves the task message ready for a turn.
            ;; Join the producers before attributing any write to publication.
            _ (#'cluster/disarm-agents! old-world)
            old-basis (:max-tx @old-connection)
            refreshed (cluster/refresh-source!
                       root ["src/seon/ai/tokens.cljc"])
            artifact
            (edn/read-string (slurp (cluster/source-artifact-file root)))
            roster
            (registry/roster (:seon.store/store old-world))]
        (testing "the one published source branch agrees with its artifact"
          (is (= source/current-branch (:seon.source/branch refreshed)))
          (is (= current-digest (:seon.source/digest refreshed)))
          (is (false? (:seon.source/built? refreshed))
              "an unchanged reported file reuses the published source head")
          (is (uuid? (:seon.source/commit-id refreshed)))
          (is (= (:seon.source/commit-id refreshed)
                 (:seon.source/commit-id artifact)))
          (is (seq (:seon.source/relative-file-digests artifact)))
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

(deftest ^{:seon.test/fixture-observation "Counts real analyzer calls while refreshing a cloned canonical publication with stale checkout paths."
           :seon.test/long "One complete canonical source analysis."}
  relocated-manifest-requires-one-complete-analysis
  (let [root (published-root)
        artifact-path (cluster/source-artifact-file root)
        artifact (edn/read-string (slurp artifact-path))
        stale (-> artifact
                  (assoc-in [:seon.fn/manifest :seon.fn.manifest/relative-roots]
                            ["/former-checkout/src" "/former-checkout/test"])
                  (update :seon.source/relative-file-digests
                          #(into {} (map (fn [[path digest]]
                                          [(str "/former-checkout" path) digest])) %)))
        calls (atom [])
        analyze analyzer/analyze]
    (try
      (spit artifact-path (pr-str stale))
      (with-redefs-fn
        {#'analyzer/analyze
         (fn [request]
           (swap! calls conj (count (:seon.fn.analyzer/sources request)))
           (analyze request))}
        #(cluster/refresh-source! root ["src/seon/ai/tokens.cljc"]))
      (is (= 1 (count @calls)) (pr-str @calls))
      (is (= (count (get-in artifact [:seon.fn/manifest :seon.fn.manifest/artifacts]))
             (first @calls))
          "the sole analysis covers the complete manifest, with no discarded per-file pass")
      (finally
        (spit artifact-path (pr-str artifact))))))

(deftest development-reload-follows-declared-requires
  (let [namespace-name (symbol (str "reload-resource-probe-" (random-uuid)))]
    (create-ns namespace-name)
    (try
      (is (not (#'cluster/reloadable-namespace? namespace-name))
          "an existing namespace without classpath source cannot be required")
      (is (#'cluster/reloadable-namespace? 'seon.cluster))
      (finally (remove-ns namespace-name))))
  ;; Dependencies reload before their consumers after the turn owners merge.

  (testing "a changed callee reloads before every changed caller, ties by name"
    (is (= '[my.plan seon.turn seon.cluster]
           (cluster/reload-order
            '#{seon.turn seon.cluster my.plan}
            '{seon.turn #{my.plan}
              seon.cluster #{seon.turn seon.db}}))))
  (testing "requires outside the changed set do not block reload"
    (is (= '[seon.turn]
           (cluster/reload-order '#{seon.turn}
                                 '{seon.turn #{seon.turn}}))))
  (testing "no edges is plain name order and an empty set is empty"
    (is (= '[a.b a.c] (cluster/reload-order '#{a.c a.b} {})))
    (is (= [] (cluster/reload-order #{} {})))))

(deftest ^{:seon.test/long
           "Real source publication and two cohosted clusters verify named adoption and independent program facts."
           ;; Two real boots plus a publication do not finish inside the
           ;; ordinary per-exchange bound: this expired as an exchange failure
           ;; in batches 68, 71 and 74. Since 8c2f62701 the bound DERIVES from
           ;; this declaration instead of standing in for it, so the cost is
           ;; stated here where the test knows it.
           :seon.test/long-ms 600000}
  development-adoption-targets-one-of-two-cohosted-clusters
  (let [root (bare-root)
        extra-root (doto (io/file root "source") .mkdirs)
        path (io/file extra-root "adoption_probe.clj")
        write-value! (fn [value]
                       (spit path
                             (str "(ns adoption-probe)\n"
                                  "(defn value {:malli/schema [:=> [:cat] :int]} [] "
                                  value ")\n")))
        roots (conj seon.fn/source-roots (.getCanonicalPath extra-root))]
    (try
      (write-value! 1)
      (with-redefs [seon.fn/source-roots roots
                       cluster/source-roots (conj roots "config/default.edn")]
           (let [fork (cluster/refresh-source! root)
                 default (cluster/start! {:seon.boot/root root
                                          :seon.boot/cluster-name "default"})]
             (try
               (let [beta (cluster/start! {:seon.boot/root root
                                           :seon.boot/cluster-name "beta"})]
                 (try
                   (let [connection (:seon.boot/cluster-connection default)
                         _ (await-bootstrap! connection "root")
                         _ (await-bootstrap! (:seon.boot/cluster-connection beta) "root")
                         ;; The stages this test observes the page through.
                         ;; The expectation below DERIVES from this one set:
                         ;; a hand-written response count also mirrored how
                         ;; many times adoption runs, so a spurious second
                         ;; adoption pass read as an arithmetic surprise.
                         adoption-stages #{"development schema declarations"
                                           "development loaded definitions"
                                           "development SCI acquisition"
                                           "development JVM instrumentation"}
                         responses (atom [])
                         url (str (get-in default [:seon.render.web/served :seon.render.web/url])
                                  (route/path ::route/agent-debug {:id "root"}))
                         observe-page! (fn [phase]
                                         (when (contains? adoption-stages phase)
                                           (let [request (.openConnection
                                                          (.toURL (java.net.URI. url)))]
                                             (.setConnectTimeout request 5000)
                                             (.setReadTimeout request 15000)
                                             (try (swap! responses conj
                                                         [phase (.getResponseCode request)])
                                                  (finally (.disconnect request))))))
                         beta-digest (fn []
                                       (db/q '[:find ?digest .
                                               :where [_ :seon.source/digest ?digest]]
                                             @(:seon.boot/cluster-connection beta)))
                         adopted (fn [conn name]
                                   (:seon.source/commit-id
                                    (db/pull @conn [:seon.source/commit-id]
                                             [:seon.cluster/name name])))
                         definition (fn [instance]
                                      (:seon.fn/source
                                       (db/pull @(:seon.boot/cluster-connection instance)
                                                [:seon.fn/source]
                                                [:seon.fn/sym "adoption-probe/value"])))]
                     (is (= (:seon.source/digest fork) (beta-digest)))
                     (is (= (definition default) (definition beta)))
                     (is (str/includes? (definition beta) "[] 1"))
                     (test-support/transacted!
                      connection
                      [{:db/id [:seon.cluster/name "default"]
                        :seon.source/commit-id (random-uuid)}])
                     (write-value! 2)
                     (let [published (binding [cluster/*source-progress!* observe-page!]
                                       (cluster/refresh-source!
                                        root [(.getCanonicalPath path)] "default"))]
                       (is (= #{200} (set (map second @responses)))
                           "the real debug page remains served during every adoption stage")
                       (is (= (zipmap adoption-stages (repeat 1))
                              (frequencies (map first @responses)))
                           "one publication adopts once: every stage runs exactly once")
                       (is (identical?
                            (get-in default [:seon.render.web/served :seon.render.web/server])
                            (get-in @@(ns-resolve 'seon.cluster 'running-instances)
                                    ["default" :seon.render.web/served :seon.render.web/server])))
                       (is (not= (:seon.source/commit-id fork)
                                 (:seon.source/commit-id published)))
                       (is (= (:seon.source/commit-id published)
                              (adopted connection "default")))
                       (is (= (:seon.source/commit-id published)
                              (:seon.source/commit-id
                               (edn/read-string
                                (slurp (cluster/source-artifact-file root))))))
                       (is (= (:seon.source/digest fork) (beta-digest))
                           "scheduled maintenance may advance beta's branch head, never its program")
                       (is (str/includes? (definition default) "[] 2"))
                       (is (str/includes? (definition beta) "[] 1"))
                       (let [held (:seon.store/store default)
                             source-before (source/database held (:seon.source/commit-id published))
                             cluster-before @connection
                             phases (atom [])]
                         (write-value! 3)
                         (let [next-publication
                               (binding [cluster/*source-progress!* #(swap! phases conj %)]
                                 (cluster/refresh-source! root [(.getCanonicalPath path)] "default"))
                               source-after (source/database held (:seon.source/commit-id next-publication))
                               source-datoms (filter #(> (:tx %) (:max-tx source-before))
                                                     (d/datoms (d/history source-after) :eavt))
                               cluster-datoms (filter #(> (:tx %) (:max-tx cluster-before))
                                                      (d/datoms (d/history @connection) :eavt))]
                           (is (= (:seon.source/commit-id next-publication)
                                  (adopted connection "default")))
                           (is (some #(str/starts-with? % "incremental scalar publication") @phases))
                           (is (<= 1 (- (:max-tx source-after) (:max-tx source-before)) 2))
                           (is (< (count source-datoms) 2000) (str "source datoms: " (count source-datoms)))
                           (is (< (count cluster-datoms) 500)
                               (str "cluster datoms: " (count cluster-datoms)
                                    "; top attribute namespaces: "
                                    (pr-str
                                     (take 10
                                           (sort-by (comp - val)
                                                    (frequencies
                                                     (map #(keyword (namespace (:a %)) "*")
                                                          cluster-datoms)))))))
                           (d/release-materialized-db source-after))
                         (d/release-materialized-db source-before))))
                   (finally (cluster/stop! beta))))
               (finally (cluster/stop! default)))))
      (finally (delete-recursively! root)))))

(deftest unchanged-complete-source-refresh-reuses-the-published-head
  (let [digest (apply str (repeat 64 "a"))
        commit-id (random-uuid)
        snapshot {:seon.source/digest digest
                  :seon.source/relative-file-digests {"src/example.clj" digest}}
        manifest {:seon.fn.manifest/root (fs/source-directory)
                  :seon.fn.manifest/relative-roots ["src"]
                  :seon.fn.manifest/digest digest
                  :seon.fn.manifest/artifacts []
                  :seon.fn.manifest/identities []}
        artifact (assoc snapshot
                        :seon.source/commit-id commit-id
                        :seon.fn/manifest manifest)
        expected {:seon.source/branch source/current-branch
                  :seon.source/commit-id commit-id
                  :seon.source/digest digest
                  :seon.source/built? false}
        publications (atom 0)]
    (with-redefs-fn
      {#'cluster/current-source-snapshot (fn [_] snapshot)
       #'cluster/read-source-artifact (fn [_] nil)
       #'source/current
       (fn [_] {:seon.source/branch source/current-branch
                :seon.source/commit-id commit-id})
       #'cluster/current-publication (fn [_ _] expected)
       #'source/publish!
       (fn [& _] (swap! publications inc) expected)}
      (fn []
        (is (= expected (#'cluster/full-source-refresh!
                         "root" ::store (#'cluster/publication-roots))))
        (is (zero? @publications)
            "the database digest owns currentness even without an artifact")))))

(deftest ^{:seon.test/fixture-observation "The test observes reopen-time configuration repair before real boot consumers acquire their settings."} ^{:seon.test/long
           "53.139 s pool: real boot, locked-state config repair, restart, and pre-arm fact proof."}
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
        ;; ONE attribute on the cluster's EXISTING config entity is a datom,
        ;; not an identity-keyed map: a map carrying `:seon.config/cluster` is
        ;; read against the whole `:seon.config/entity` schema, which requires
        ;; the applied manifest digest the config owner mints — the fixture
        ;; would be writing a config row no mechanism can produce.
        (test-support/transacted!
                     connection
                     {:tx-data
                      [[:db/add [:seon.config/cluster cluster-name]
                        :seon.config.flow.compute/queue-depth 1]]})
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


(deftest current-source-digest-names-the-merged-schema-declarations
  (let [schema-path "resources/seon/schemas"]
    (is (= (schema.edn/declaration-digest)
           (get (:seon.source/relative-file-digests (cluster/source-snapshot))
                schema-path))
        "the ancestor hashes the merged schema declaration set"))
  (is (not-any? #{"resources/seon/bootstrap.edn"} cluster/source-roots)
      "generated openings have no authored bootstrap resource")
  (is (not-any? #{"resources" "resources/seon/schemas"} cluster/source-roots)
      "schema directory organization is not part of the ancestor digest"))

(deftest ^{:seon.test/long
           "Publishes real source edits to cover complete fallback and incremental branch agreement."}
  incremental-source-refresh-preserves-agreement-across-real-edits
  (let [complete-builds (atom 0)
        build-manifest seon.fn/build-manifest
        root (bare-root)
        project (io/file root "project")
        source-root (io/file project "src")
        test-root (io/file project "test")
        resource-root (io/file project "resources")
        schema-path (write-source! resource-root "seon/schema.edn" "{}\n")
        a-path (write-source! source-root "sample/a.clj"
                              "(ns sample.a)\n(defn value [] 1)\n")
        b-path (write-source! source-root "sample/b.clj"
                              "(ns sample.b)\n(defn value [] 10)\n")
        roots (into seon.fn/source-roots
                    (map #(.getCanonicalPath ^java.io.File %)
                         [source-root test-root]))
        all-roots (conj roots schema-path)]
    (.mkdirs test-root)
    (try
      (with-redefs [seon.fn/source-roots roots
                    cluster/source-roots all-roots
                    seon.fn/build-manifest (fn [request]
                                             (swap! complete-builds inc)
                                             (build-manifest request))]
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

        (testing "a missed X followed by reported Y repairs both incrementally"
          (write-source! source-root "sample/a.clj"
                         "(ns sample.a)\n(defn value [] 4)\n")
          (write-source! source-root "sample/b.clj"
                         "(ns sample.b)\n(defn value [] 20)\n")
          (cluster/refresh-source! root [b-path])
          (let [opened (store/open-store!
                        {:seon.store/dir (derived-store-dir root)})]
            (try
              (let [db (d/branch-as-db (:seon.store/connection-object opened)
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
                (store/release-store! opened)))))
        (testing "body keyword metadata reconciles without complete analysis"
          (write-source! source-root "sample/a.clj"
                         "(ns sample.a)\n(defn value [] :sample/new-value)\n")
          (cluster/refresh-source! root [a-path])
          (is (= 1 @complete-builds)
              "scalar, missed-path, and metadata edits reuse the manifest")))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/fixture-observation "The test observes ordered REPL, store, population and flow readiness during real boot and sibling-cluster acquisition."} ^{:seon.test/long
           "46.987 s pool: complete real boot plus sibling-cluster acquisition and independent config proof."}
  boot-order-completes-in-one-start
  (let [root (published-root)]
    (try
      (let [started-at (System/nanoTime)
            phases (atom [])
            progress-var (ns-resolve 'seon.cluster '*boot-progress!*)
            instance
            (with-bindings
              {progress-var #(swap! phases conj %)}
              (cluster/start! {:seon.boot/cluster-name "boot-order"
                               :seon.boot/root root
                               :seon.config/manifest
                               {:seon.config.flow.compute/queue-depth 11}}))
            elapsed-ms (/ (- (System/nanoTime) started-at) 1e6)]
        (try
          (testing "every boot field is present — nothing degraded"
            (is (some? (:seon.store/store instance)))
            (is (store/connection?
                 (:seon.boot/cluster-connection instance)))
            (is (map? (:seon.boot/config-result instance))))
          (testing "the whole boot reports its measured duration"
            (is (nat-int? (:seon.boot/ready-ms instance)))
            (is (<= (:seon.boot/ready-ms instance) elapsed-ms)))
          (testing "each published boot boundary reports one phase"
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
          (testing "bootstrap settles one generated opening turn"
            (let [connection (:seon.boot/cluster-connection instance)
                  run-id (bootstrap/run-id "root")
                  _ (await-bootstrap! connection "root")
                  database @connection
                  session
                  (transcript/render-ai
                   {:seon.db/db database
                    :seon.sci.eval/ctx (:seon.sci.eval/ctx instance)
                    :seon.sci.eval/time-limit-ms 1000
                    :seon.config/on-core-error :record
                    :seon.agent/id "root"
                    :seon.sci.admit/caps
                    (config/result-caps (config/defaults))})
                  ;; The shipped bootstrap episode is the GENERATED OPENING,
                  ;; not a hand-authored worked episode: `6aca09cce`
                  ;; ("Use the system-turn generator for seeded agent
                  ;; openings") replaced this run's derivation with the shared
                  ;; system-turn source generator — "Creation and later system
                  ;; turns derive their sources from the same record walk"
                  ;; (`src/seon/turn.clj:4886`). The `(defn largest …)` /
                  ;; `(run/complete …)` forms this once scanned for were the
                  ;; hand-authored episode of `37df160dd`
                  ;; (`resources/seon/bootstrap.edn`, deleted); `largest` is
                  ;; now the ASSIGNMENT the opening delivers
                  ;; (`seon.bootstrap/task-message`), performed by a model, so
                  ;; no boot completes it. What this turn must show is the
                  ;; REPL's own help, the agent's own record reads, and the
                  ;; assignment it was triggered with; that it CLOSED is
                  ;; already proven by `await-bootstrap!` above.
                  help-index (.indexOf session "(help)")
                  message-index (.indexOf session "(my.message/read")
                  plan-index (.indexOf session "(seon.plan/plan {})")
                  assignment-index (.indexOf session (bootstrap/task-message))]
              (is (= (db/q '[:find (count ?form) .
                             :in $ ?run-id
                             :where
                             [?run :seon.turn/id ?run-id]
                             [?form :seon.cluster.eval/run ?run]]
                           database run-id)
                     (db/q '[:find (count ?receipt) .
                             :in $ ?run-id
                             :where
                             [?run :seon.turn/id ?run-id]
                             [?receipt :seon.cluster.eval/run ?run]]
                           database run-id))
                  "every successful form settles with a real receipt")
              (is (< -1 help-index message-index plan-index assignment-index)
                  "the generated opening leads with help, reads the trigger message and the plan, and delivers the seeded assignment")
              (is (not (str/includes? session "uses :any")))
              (is (empty?
                   (db/q '[:find ?error
                           :in $ ?run-id
                           :where
                           [?run :seon.turn/id ?run-id]
                           [?error :seon.error/run ?run]]
                         database run-id))
                  "an agent evaluation error never enters the core-fault family")))
          (testing "a second cluster acquires its own projection from the
                    shared store"
            (let [sibling (cluster/start!
                           {:seon.boot/cluster-name "twr2"
                            :seon.boot/root root
                            :seon.config/manifest
                            {:seon.config.flow.compute/queue-depth 22}})]
              (try
                (is (some? (:seon.boot/cluster-connection sibling)))
                (is (identical? (:seon.store/store instance)
                                (:seon.store/store sibling))
                    "siblings share the ONE process-root store")
                (is (= 11
                       (:seon.config.flow.compute/queue-depth
                        (config/effective
                         @(:seon.boot/cluster-connection instance)
                         "boot-order"))))
                (is (= 22
                       (:seon.config.flow.compute/queue-depth
                        (config/effective
                         @(:seon.boot/cluster-connection sibling)
                         "twr2")))
                    "two clusters in one JVM retain distinct applied configs")
                (finally
                  (cluster/stop! sibling)))))
          (finally
            (cluster/stop! instance))))
      (finally
        (delete-recursively! root)))))

(deftest ^{:seon.test/long "Starts a real prepl over deliberately corrupted boot storage."}
  a-failed-boot-never-takes-the-repl
  ;; owner ruling: the REPL is always useful for debugging — a corrupt
  ;; store fails the boot LOUDLY while the socket stays up
  (let [root (bare-root)]
    (try
      ;; a FILE where the store directory belongs corrupts layer 1
      (.mkdirs (.getParentFile (io/file (derived-store-dir root))))
      (spit (derived-store-dir root) "not a store")
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
            "the boot fields are absent from the failure point")
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

(deftest ^{:seon.test/long
           "192.600 s pool: complete publication, real start, destructive composed refork, replacement boot, and read-back."}
  explicit-refork-destroys-the-old-branch-and-forks-current-source
  ;; The COMPOSED verb, on a real store, in the operator's OWN layout:
  ;; `bin/seon init NAME --force` is exactly this call
  ;; (`script/seon/fresh_operator.clj:1941`), so the claim, the managed
  ;; root, and the published commit are supplied the way the operator
  ;; supplies them.
  ;;
  ;; The earlier form called `seon.cluster/refork!` — a second path that
  ;; GUESSED the managed root two parents up from the cluster root. That
  ;; guess named an unrelated directory for any root not shaped like the
  ;; operator's, so cleanup found no claim, the composed refork never
  ;; ran, and the flat error value read back through a keyword lookup as
  ;; `nil`. The guessing duplicate is deleted; the class is "a second
  ;; path derives a required input instead of receiving it", and a
  ;; refusal is asserted loudly here with the returned value in hand.
  (let [repository-root (.getCanonicalPath (io/file (bare-root)))
        managed-root (.getCanonicalPath (io/file repository-root "managed"))
        cluster-root (str (io/file managed-root "data" "clusters"))
        cluster-name "refork-program"]
    (try
      (.mkdirs (io/file cluster-root))
      (let [published (cluster/refresh-source! cluster-root)
            claim (operator/claim-root!
                   {:seon.operator/repository-root repository-root
                    :seon.operator/managed-root managed-root
                    :seon.boot/cluster-name cluster-name})
            instance (cluster/start! {:seon.boot/cluster-name cluster-name
                                      :seon.boot/root cluster-root})]
        (is (contains? (:seon.operator.claim/clusters claim) cluster-name)
            (str "the refork target must be exactly claimed: "
                 (pr-str claim)))
        (try
          (test-support/transacted! (:seon.boot/cluster-connection instance)
                                    [{:seon.agent/id "history-refork-recipient"}
                                     {:seon.message/id "history-refork-destroys"
                                      :seon.message/to [:seon.agent/id "history-refork-recipient"]
                                      :seon.message/content "history-refork-destroys"}])
          (let [result (operator/refork!
                        {:seon.operator/repository-root repository-root
                         :seon.operator/managed-root managed-root
                         :seon.boot/cluster-name cluster-name
                         :seon.source/commit-id
                         (:seon.source/commit-id published)
                         :seon.store/store (:seon.store/store instance)})
                replacement
                (cluster/start! {:seon.boot/cluster-name cluster-name
                                 :seon.boot/root cluster-root})]
            (try
              (testing "the old branch was replaced from current-src"
                (is (true? (:seon.cluster/created? result))
                    (str "the composed refork must create the branch: "
                         (pr-str result)))
                (is (nil?
                     (db/q '[:find ?message .
                             :where
                             [?message :seon.message/id
                              "history-refork-destroys"]]
                           @(:seon.boot/cluster-connection replacement)))
                    "the destroyed branch's data must not survive")
                (is (pos?
                     (or
                      (db/q '[:find (count ?function) .
                              :where [?function :seon.fn/sym]]
                            @(:seon.boot/cluster-connection replacement))
                      0))
                    "the replacement carries the published program graph"))
              (finally
                (cluster/stop! replacement))))
          (finally
            ;; the composed refork stops it. Idempotent cleanup for a
            ;; failure before that boundary.
            (cluster/stop! instance))))
      (finally
        (delete-recursively! repository-root)))))

(deftest ^{:seon.test/long
           "158.206 s pool: published real store plus child-JVM operator refork and collision/read-back proof."}
  refork-does-not-collide-with-the-store-its-caller-already-holds
  ;; The second shape of the same class. The sibling test above supplies a
  ;; RUNNING instance's store, which the fork arm could also find in the
  ;; running-instance holder table, so the collision stayed hidden. The
  ;; operator's own `init NAME --force` has no running instance: its child
  ;; JVM opens the store directly (`script/seon/fresh_operator.clj`,
  ;; `named-init-form`) and hands it to the composed verb. The fork arm
  ;; then asked for a store while ignoring the one it was given, opened a
  ;; second one, and the flock refused the command's collision with
  ;; ITSELF — after the destroy arm had already retired the branch. The
  ;; class is "an arm re-opens a resource its own caller still holds"; the
  ;; branch existing afterwards is what proves it dead.
  (let [repository-root (.getCanonicalPath (io/file (bare-root)))
        managed-root (.getCanonicalPath (io/file repository-root "managed"))
        cluster-root (str (io/file managed-root "data" "clusters"))
        cluster-name "refork-selfheld"]
    (try
      (.mkdirs (io/file cluster-root))
      (let [published (cluster/refresh-source! cluster-root)]
        (operator/claim-root!
         {:seon.operator/repository-root repository-root
          :seon.operator/managed-root managed-root
          :seon.boot/cluster-name cluster-name})
        (let [opened (store/open-store!
                      {:seon.store/dir (derived-store-dir cluster-root)})]
          (try
            (registry/ensure-cluster!
             {:seon.store/store opened
              :seon.boot/cluster-name cluster-name
              :seon.source/commit-id (:seon.source/commit-id published)})
            (is (contains? (registry/roster opened)
                           (registry/cluster-branch cluster-name)))
            (let [result (operator/refork!
                          {:seon.operator/repository-root repository-root
                           :seon.operator/managed-root managed-root
                           :seon.boot/cluster-name cluster-name
                           :seon.source/commit-id
                           (:seon.source/commit-id published)
                           :seon.store/store opened})]
              (is (true? (:seon.cluster/created? result))
                  (str "the refork must not refuse its caller's own store: "
                       (pr-str result)))
              (is (contains? (registry/roster opened)
                             (registry/cluster-branch cluster-name))
                  "the branch must exist after the forced refork"))
            (finally
              (store/release-store! opened)))))
      (finally
        (delete-recursively! repository-root)))))

(deftest a-second-store-open-in-this-process-says-so
  ;; `::held-elsewhere` was a false statement whenever the holder was us,
  ;; and it is the reason the refork above read as environment churn: the
  ;; reader goes looking for an orphan process that does not exist. The
  ;; process's own holdings are a fact this namespace already records.
  (let [root (bare-root)
        dir (str (io/file root "store"))
        opened (store/open-store! {:seon.store/dir dir})]
    (try
      (let [refusal (try
                      (store/open-store! {:seon.store/dir dir})
                      (catch clojure.lang.ExceptionInfo error
                        (ex-data error)))]
        (is (= :seon.cluster.store/held-by-this-process
               (:seon.cluster.store/rule refusal))
            (pr-str refusal))
        (is (str/includes? (str (:seon.cluster.store/dir refusal)) "store")
            (pr-str refusal)))
      (finally
        (store/release-store! opened)
        (delete-recursively! root)))))

;;; ---------------------------------------------------------------------------
;;; Boot recovery — a dead holder's wreckage is settled before anything resumes
;;; ---------------------------------------------------------------------------

(deftest ^{:seon.test/fixture-observation "The subject is durable state recovered after an actual JVM kill and physical-store reopen."} ^{:seon.test/long
           "Kills a child JVM mid-generation and proves same-run continuation."}
  a-generated-prefix-resumes-on-the-same-run-after-jvm-kill
  (let [root (published-root)
        cluster-name "generated-resume"
        java-command (.getPath
                      (File. (System/getProperty "java.home") "bin/java"))
        process (-> (ProcessBuilder.
                     ^java.util.List
                     [java-command
                      "-cp" (System/getProperty "java.class.path")
                      "clojure.main"
                      "-m" "seon.cluster.bootstrap-resume-child"
                      root cluster-name])
                    (.redirectErrorStream true)
                    (.start))
        readiness (CompletableFuture.)
        child-output (atom [])
        output-reader
        (future
          (try
            (with-open [reader (io/reader (.getInputStream process))]
              (loop []
                (when-let [line (.readLine reader)]
                  (swap! child-output conj line)
                  (if (= (str "deriving " (bootstrap/run-id "root")) line)
                    (.complete readiness ::deriving)
                    (recur)))))
            (catch java.io.IOException error
              (.completeExceptionally readiness error))))
        _ (.thenAccept
           (.onExit process)
           (reify java.util.function.Consumer
             (accept [_ exited]
               (.complete readiness exited))))]
    (try
      (let [observed (.get readiness 60 TimeUnit/SECONDS)]
        (when (instance? Process observed)
          (throw
           (ex-info "The child JVM exited before entering derivation."
                    {:seon.error/kind ::child-exited-before-derivation
                     :seon.test/exit (.exitValue process)
                     :seon.test/output (str/join "\n" @child-output)}))))
      (.destroyForcibly process)
      (test-support/await-event! (.onExit process) ::child-exit-after-kill)
      (let [instance (cluster/start! {:seon.boot/cluster-name cluster-name
                                      :seon.boot/root root})
            connection (:seon.boot/cluster-connection instance)
            run-id (bootstrap/run-id "root")]
        (try
          (await-fact
           connection
           (fn [database]
             (db/q '[:find ?receipt .
                     :in $ ?run-id
                     :where
                     [?run :seon.turn/id ?run-id]
                     [?receipt :seon.cluster.eval/run ?run]
                     [?receipt :seon.cluster.eval/ordinal 1]
                     (or [?receipt :seon.cluster.eval/result-edn _]
                         [?receipt :seon.cluster.eval/error _])]
                   database run-id)))
          (let [database @connection
                run (db/pull database
                             '[:seon.turn/id
                               :seon.turn/closed-tx
                               {:seon.turn/trigger
                                [:seon.message/id]}]
                             [:seon.turn/id run-id])
                ordinals
                (db/q {:query
                       '[:find [?ordinal ...]
                         :in $ ?run-id
                         :where
                         [?run :seon.turn/id ?run-id]
                         [?form :seon.cluster.eval/run ?run]
                         [?form :seon.cluster.eval/ordinal ?ordinal]]
                       :args [database run-id]
                       :order-by '[?ordinal :asc]})
                error-kinds
                (into #{}
                      (db/q '[:find [?kind ...]
                              :where [_ :seon.error/kind ?kind]]
                            database))]
            (is (= run-id (:seon.turn/id run)))
            ;; The killed turn comes back CLOSED. `34e47f595` (2026-09-09,
            ;; "Close all prior open turns during boot recovery") deleted the
            ;; exemption this once asserted — "A generated run stays open and
            ;; attached: its settled receipts are the append-only derivation
            ;; prefix" — and `seon.turn/recover-call` now rules that "a saved
            ;; holder or a generated-source tag cannot exempt an open turn"
            ;; (`src/seon/turn.clj:1793`), the crash model's "interrupted
            ;; execution never resumes". What must survive the kill is below:
            ;; the SAME run keeps its derived prefix and its trigger, and no
            ;; second run answers that trigger.
            (is (some? (:seon.turn/closed-tx run)))
            (is (= (bootstrap/task-message-id database "root")
                   (get-in run [:seon.turn/trigger
                                :seon.message/id])))
            (is (= [0 1] (vec (take 2 (sort ordinals)))))
            (is (= [run-id]
                   (db/q '[:find [?run-id ...]
                           :in $ ?trigger-id
                           :where
                           [?trigger :seon.message/id ?trigger-id]
                           [?run :seon.turn/trigger ?trigger]
                           [?run :seon.turn/id ?run-id]]
                         database (bootstrap/task-message-id database "root"))))
            (is (not (contains? error-kinds
                                :seon.bootstrap/prefix-drift)))
            (is (not (contains? error-kinds
                                :seon.turn.loop/trigger-already-answered))))
          (finally
            (schema/call-with-projection
             (schema/projection-from-database @connection)
             #(cluster/stop! instance)))))
      (finally
        (when (.isAlive process)
          (.destroyForcibly process)
          (.join (.onExit process)))
        (future-cancel output-reader)))))

(deftest ^{:seon.test/fixture-observation "Recovery must close persisted interrupted work while reopening a stopped cluster from its physical store."} ^{:seon.test/long
           "60.475 s pool: real boot, simulated dead holder, restart recovery, and custody read-back."}
  a-dead-holders-run-is-unclaimed-by-the-time-start-returns
  ;; The live crash drill found this gap: `recover-tx` existed with no
  ;; caller, so a process that died holding a claimed run left the agent
  ;; wedged — `work/next-agent-work` sees a run held by someone else and
  ;; returns nothing, forever. Recovery is BY FACT: the drill measured
  ;; the dead holder's 60-second lease still in the future when the fix
  ;; fires, so nothing here waits a lease out.
  (let [root (published-root)
        ;; the reply whose forms were executing when the process died: the
        ;; "plan intact" this test claims is a fact of THIS turn, so it is
        ;; seeded here instead of being read off whatever other turn in the
        ;; store happened to carry one
        crashed-reply "(+ 1 1)"]
    (try
      ;; a first boot writes the wreckage a kill -9 mid-model-call leaves:
      ;; an open run claimed by a process that will not exist afterwards,
      ;; a live lease, and a dangling :running receipt
      (let [instance (cluster/start! {:seon.boot/cluster-name "recov"
                                      :seon.boot/root root})
            connection (:seon.boot/cluster-connection instance)
            now (java.util.Date.)]
        (await-bootstrap! connection "root")
        (test-support/transacted! connection [{:seon.agent/id "alice"}
                                              {:seon.agent/id "bob"}])
        ;; the CONTROL, seeded in the same generation and identical
        ;; except for how it ends: a run that closed the ordinary way.
        ;; Without it, "recovery marked the run" proves nothing — the
        ;; class this test kills is that a recovered run and a normal
        ;; close were the SAME facts (whole-system-arc observer,
        ;; 2026-08-08: `945f3226` closed by recovery with no marker
        ;; anywhere durable, so the honesty claim died with its JVM)
        (test-support/transacted! connection
                                [{:seon.turn/id "run-clean" :seon.turn/agent [:seon.agent/id "bob"] :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx"}])
        (test-support/transacted! connection
                                [{:seon.turn/id "run-crashed" :seon.turn/agent [:seon.agent/id "alice"] :seon.turn/opened-tx "datomic.tx"
                                  :seon.turn/reply crashed-reply
                                  :seon.turn/reply-size (count crashed-reply)}
                                 {:seon.agent/id "alice"
                                  }
                                 ;; dangling = started with no terminal fact —
                                 ;; running IS that absence, there is no status
                                 {:seon.cluster.eval/id "e-0"
                                  :seon.cluster.eval/run [:seon.turn/id "run-crashed"]
                                  :seon.cluster.eval/ordinal 0
                                  :seon.cluster.eval/source "(+ 1 1)"
                                  :seon.cluster.eval/at now}])
        (cluster/stop! instance))

      ;; the next boot must settle it, with no lease wait
      (let [instance (cluster/start! {:seon.boot/cluster-name "recov"
                                      :seon.boot/root root})
            connection (:seon.boot/cluster-connection instance)]
        (try

          (testing "its dangling receipt carries interrupted-at — the
                    one terminal fact recovery asserts"
            (is (= 1 (count
                      (db/q (quote [:find [?at ...] :where
                                   [_ :seon.cluster.eval/interrupted-at ?at]])
                           @connection)))))
          (testing "and the run is CLOSED with its plan intact —
                    recovery ends custody and no plan form can execute"
            ;; both facts are read off THIS turn. An unbound `[_ ...]` query
            ;; answered from any turn in the store, so it reported health
            ;; from a bootstrap opening rather than from recovery's subject.
            (let [crashed (db/pull @connection
                                   '[:seon.turn/reply :seon.turn/reply-size
                                     {:seon.turn/closed-tx [:db/id :db/txInstant]}]
                                   [:seon.turn/id "run-crashed"])]
              (is (some? (:seon.turn/closed-tx crashed)))
              (is (= crashed-reply (:seon.turn/reply crashed)))
              (is (= (count crashed-reply) (:seon.turn/reply-size crashed))
                  "recovery ended custody without touching the reply it held")))
          (testing "boot closes the interrupted turn and preserves the clean turn"
            ;; `:seon.turn/closed-tx` is a REF to the closing transaction
            ;; (`seon.turn.edn` :closed-tx), so the instant is one hop away;
            ;; pulling the attribute itself can only ever answer `{:db/id n}`.
            (is (inst? (:db/txInstant
                        (:seon.turn/closed-tx
                         (db/pull @connection
                                  '[{:seon.turn/closed-tx [:db/txInstant]}]
                                  [:seon.turn/id "run-crashed"])))))
            (is (str/includes?
                 (turn/render-ai
                  (assoc (db/pull @connection '[*]
                                  [:seon.turn/id "run-crashed"])
                         :seon.db/db @connection))
                 "interrupted")))
          (testing "and the instance reports what recovery did"
            ;; DERIVED, never a fixed number: recovery closes every open turn
            ;; in ONE transaction, so the turns carrying that transaction as
            ;; their `closed-tx` ARE what it recovered. A literal 1 mirrored
            ;; how many other turns a boot happened to leave open — a
            ;; bootstrap opening still open at `stop!` made it 2, which says
            ;; nothing about whether the dead holder's run was unclaimed.
            (let [closing-tx (:db/id (:seon.turn/closed-tx
                                      (db/pull @connection
                                               '[{:seon.turn/closed-tx [:db/id]}]
                                               [:seon.turn/id "run-crashed"])))]
              (is (some? closing-tx))
              (is (= (count (db/q '[:find [?turn ...] :in $ ?tx :where
                                    [?turn :seon.turn/closed-tx ?tx]]
                                  @connection closing-tx))
                     (:seon.boot/recovered-runs instance))
                  "the report names exactly the turns this recovery closed"))
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

(deftest ^{:seon.test/fixture-observation "Relocates the canonical published checkout and observes real analyzer calls on an unchanged clone and one edit."
           :seon.test/long "Copies the canonical checkout and publishes one file edit."}
  cloned-publication-analyzes-only-changed-files
  (let [root (published-root)
        ;; A pooled worker's files need not match its cached published base.
        ;; Establish this test's baseline before relocating or counting work.
        baseline (cluster/refresh-source! root [])
        checkout (bare-root)
        directory (.getCanonicalPath (io/file checkout))
        original-roots (#'cluster/publication-roots)
        roots (assoc original-roots :seon.fn/root directory)
        calls (atom [])
        analyze analyzer/analyze
        path (io/file checkout "src/seon/ai/tokens.cljc")]
    (try
      (doseq [relative (:seon.source/roots roots)]
        (let [source (io/file (fs/source-directory) relative)
              target (io/file checkout relative)]
          (io/make-parents target)
          (if (.isDirectory source)
            (#'test-support/clone-directory! source target)
            (io/copy source target))))
      (with-redefs-fn
        {#'cluster/publication-roots (constantly roots)
         #'analyzer/analyze
         (fn [request]
           (swap! calls into (keys (:seon.fn.analyzer/sources request)))
           (analyze request))}
        (fn []
          (let [unchanged (cluster/refresh-source! root [])]
            (is (false? (:seon.source/built? unchanged)))
            (is (= (:seon.source/commit-id baseline)
                   (:seon.source/commit-id unchanged)))
            (is (= [] @calls) "relocation alone analyzes no file"))
          (let [artifact-file (cluster/source-artifact-file root)
                artifact (edn/read-string (slurp artifact-file))]
            (spit artifact-file
                  (pr-str (update artifact :seon.source/relative-file-digests
                                  dissoc "src/seon/ai/tokens.cljc")))
            (let [snapshot #'cluster/current-source-snapshot
                  capture @snapshot]
              (with-redefs-fn
                {snapshot (fn [publication-roots]
                            (update (capture publication-roots)
                                    :seon.source/relative-file-digests
                                    dissoc "src/seon/ai/tokens.cljc"))}
                #(cluster/refresh-source! root [(.getCanonicalPath path)])))
            (is (= [(.getCanonicalPath path)] @calls)
                "a reported file absent from both digest sets is analyzed")
            (reset! calls []))
          (spit path (str (slurp path) "\n; Relocated checkout edit.\n"))
          (cluster/refresh-source! root [(.getCanonicalPath path)])
          (is (= [(.getCanonicalPath path)] @calls)
              "the edit analyzes precisely one file through the production seam")))
      (finally (delete-recursively! checkout)))))
