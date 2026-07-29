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
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.agent]
            [seon.cluster.process :as cluster.process]
            [seon.cluster.store :as store]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.flow :as seon.flow]
            [seon.render.block :as block]
            [seon.render.root :as root-render]
            [seon.schema :as schema])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(defn- fresh-root []
  (let [root (str "tmp/boot-test/" (random-uuid))]
    (.mkdirs (io/file root))
    root))

(defn- delete-recursively! [path]
  (let [file (io/file path)]
    (when (.exists file)
      (doseq [child (reverse (file-seq file))]
        (.delete ^java.io.File child)))))

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
              ;; complete and valid against the closed schema
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
  (testing "an unknown key is refused, not ignored"
    (is (thrown? Exception
                 (cluster/resolve-bootstrap {:seon.boot/store-backend :file}))))
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

(deftest root-executors-are-one-shared-pair
  (let [first-pair (cluster/root-executors)
        second-pair (cluster/root-executors)]
    (is (identical? (:compute first-pair) (:compute second-pair))
        "repeated calls return the SAME compute executor")
    (is (identical? (:io first-pair) (:io second-pair))
        "repeated calls return the SAME io executor")))

;;; ---------------------------------------------------------------------------
;;; The live lifecycle — real sockets, real files, this JVM
;;; ---------------------------------------------------------------------------

(deftest repl-first-and-under-the-ten-second-bound
  (let [root (fresh-root)]
    (try
      (let [started-at (System/nanoTime)
            instance (cluster/start! {:seon.boot/cluster-name "solo"
                                      :seon.boot/root root})
            advertisement (:seon.boot/advertisement instance)
            answer (prepl-eval (:seon.boot/prepl-host advertisement)
                               (:seon.boot/prepl-port advertisement)
                               "(+ 20260727 1)")
            elapsed-ms (/ (- (System/nanoTime) started-at) 1e6)]
        (try
          (testing "the REPL answers with the evaluated value"
            (is (= "20260728" answer)))
          (testing "start-to-answer beats the ten-second ruling"
            (is (< elapsed-ms 10000)
                (str "start!->REPL took " elapsed-ms " ms")))
          (testing "the advertisement validates and is discoverable"
            (is (seon.schema/valid-candidate-value?
                 :seon.boot/advertisement advertisement))
            (is (= advertisement
                   (cluster/read-advertisement root "solo"))))
          (finally
            (cluster/stop! instance))))
      (finally
        (delete-recursively! root)))))

(deftest two-instances-are-isolated
  (let [root (fresh-root)]
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

(deftest stale-advertisements-read-as-absent
  (let [root (fresh-root)]
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

(deftest a-delayed-stop-never-kills-a-replacement
  ;; stops are instance-addressed: a stale stop! of an OLD instance
  ;; value must leave a same-named replacement fully alive
  (let [root (fresh-root)]
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

(deftest same-jvm-same-name-restart-releases-the-registered-prepl
  (let [root (fresh-root)
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

(deftest a-failed-stop-remains-addressable-and-retryable
  (let [root (fresh-root)
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

(deftest orderly-stop-awaits-the-active-loop-pass
  (let [root (fresh-root)
        pass-entered (CountDownLatch. 1)
        finish-pass (CountDownLatch. 1)
        stop-commanded (CountDownLatch. 1)
        transaction-outcome (promise)
        original-transact! store/transact!
        original-stop flow/stop]
    (try
      (let [instance (cluster/start! {:seon.boot/cluster-name "stopping"
                                      :seon.boot/root root})
            connection (:seon.boot/cluster-connection instance)]
        (try
          (with-redefs
            [store/transact!
             (fn [conn tx-data]
               (.countDown pass-entered)
               (.await finish-pass)
               (let [outcome (original-transact! conn tx-data)]
                 (deliver transaction-outcome outcome)
                 outcome))
             work/next-agent-work
             (fn [_db _request]
               (store/transact! connection
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
;;; The composed tower — store, ancestor, fork, config, one start!
;;; ---------------------------------------------------------------------------

(deftest reopen-accretes-a-new-config-attribute-before-config-applies
  (let [root (fresh-root)
        cluster-name "schema-reopen"
        dial :seon.config.boot-test/reopen-dial
        schema-state (schema/snapshot-state)]
    (try
      (let [instance (cluster/start! {:seon.boot/cluster-name cluster-name
                                      :seon.boot/root root})]
        (cluster/stop! instance))

      ;; This is the source-accretion boundary in miniature: the store is
      ;; closed before today's population gains one persisted config attribute.
      ;; Its one registration derives every composite and its boot default.
      (schema/register!
       dial
       [:int {:seon.db/index true :seon.config/default 7}])

      (let [instance (cluster/start! {:seon.boot/cluster-name cluster-name
                                      :seon.boot/root root})
            connection (:seon.boot/cluster-connection instance)]
        (try
          (testing "reopen installs the new declaration before config writes"
            (is (contains? (:schema @connection) dial)))
          (testing "the current canonical schema row also accretes"
            (is (= (schema/form-string dial)
                   (d/q '[:find ?form .
                          :in $ ?dial
                          :where
                          [?schema :seon.schema/key ?dial]
                          [?schema :seon.schema/form ?form]]
                        @connection
                        dial))))
          (testing "zero-overlay boot applies the newly admitted default"
            (is (= 7 (get (config/effective @connection cluster-name)
                          dial))))
          (testing "config/apply! can override the newly admitted dial"
            (let [result
                  (config/apply!
                   {:seon.config/connection connection
                    :seon.config/manifest {dial 8}
                    :seon.boot/cluster-name cluster-name})]
              (is (pos? (:seon.reconcile/operations result)))
              (is (= 8 (get (config/effective @connection cluster-name)
                            dial)))))
          (finally
            (cluster/stop! instance))))
      (finally
        (schema/restore-state! schema-state)
        (delete-recursively! root)))))

(deftest selected-config-repairs-locked-state-before-consumers-arm
  (let [root (fresh-root)
        cluster-name "config-unlock"
        observed (atom [])
        install-work-launcher! seon.flow/install-work-launcher!
        arm-agents! (var-get (ns-resolve 'seon.cluster 'arm-agents!))]
    (try
      (let [instance (cluster/start! {:seon.boot/cluster-name cluster-name
                                      :seon.boot/root root})
            connection (:seon.boot/cluster-connection instance)]
        (d/transact
         connection
         {:tx-data
          [{:seon.config/cluster cluster-name
            :seon.config.flow.compute/queue-depth 1}]})
        (cluster/stop! instance))
      (with-redefs-fn
        {#'seon.flow/install-work-launcher!
         (fn [request]
           (swap! observed conj
                  [:launcher
                   (:seon.config.flow.compute/queue-depth
                    (::seon.flow/configuration request))])
           (install-work-launcher! request))
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

(deftest the-tower-stands-in-one-start
  (let [root (fresh-root)]
    (try
      (let [started-at (System/nanoTime)
            instance (cluster/start! {:seon.boot/cluster-name "tower"
                                      :seon.boot/root root
                                      :seon.config/manifest
                                      {:seon.config.flow.compute/queue-depth
                                       11}})
            elapsed-ms (/ (- (System/nanoTime) started-at) 1e6)]
        (try
          (testing "every tower field is present — nothing degraded"
            (is (some? (:seon.store/store instance)))
            (is (store/connection?
                 (:seon.boot/cluster-connection instance)))
            (is (map? (:seon.boot/config-result instance))))
          (testing "the whole tower beats the ten-second ruling"
            (is (< elapsed-ms 10000)
                (str "start!->tower took " elapsed-ms " ms")))
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

(deftest a-failed-tower-never-takes-the-repl
  ;; owner ruling: the REPL is always useful for debugging — a corrupt
  ;; store fails the boot LOUDLY while the socket stays up
  (let [root (fresh-root)]
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

;;; ---------------------------------------------------------------------------
;;; Boot recovery — a dead holder's wreckage is settled before anything resumes
;;; ---------------------------------------------------------------------------

(deftest a-dead-holders-run-is-unclaimed-by-the-time-start-returns
  ;; The live crash drill found this gap: `recover-tx` existed with no
  ;; caller, so a process that died holding a claimed run left the agent
  ;; wedged — `work/next-agent-work` sees a run held by someone else and
  ;; returns nothing, forever. Recovery is BY FACT: the drill measured
  ;; the dead holder's 60-second lease still in the future when the fix
  ;; fires, so nothing here waits a lease out.
  (let [root (fresh-root)]
    (try
      ;; a first boot writes the wreckage a kill -9 mid-model-call leaves:
      ;; an open run claimed by a process that will not exist afterwards,
      ;; a live lease, and a dangling :running receipt
      (let [instance (cluster/start! {:seon.boot/cluster-name "recov"
                                      :seon.boot/root root})
            connection (:seon.boot/cluster-connection instance)
            now (java.util.Date.)]
        (d/transact connection [{:seon.cluster.agent/id "alice"}])
        (d/transact connection
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
      (let [started (System/nanoTime)
            instance (cluster/start! {:seon.boot/cluster-name "recov"
                                      :seon.boot/root root})
            connection (:seon.boot/cluster-connection instance)
            elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
        (try
          (testing "the dead holder's custody is gone — custody is
                    presence, and no run holds any"
            (is (nil? (d/q (quote [:find ?p . :where
                                   [_ :seon.cluster.run/process ?p]])
                           @connection))))
          (testing "its dangling receipt carries interrupted-at — the
                    one terminal fact recovery asserts"
            (is (= 1 (count
                      (d/q (quote [:find [?at ...] :where
                                   [_ :seon.cluster.eval/interrupted-at ?at]])
                           @connection)))))
          (testing "and the run is still OPEN with its plan intact —
                    recovery settles custody, it does not re-plan or
                    re-execute anything"
            (is (nil? (d/q (quote [:find ?c . :where
                                   [_ :seon.cluster.run/closed-at ?c]])
                           @connection)))
            (is (some? (d/q (quote [:find ?d . :where
                                    [_ :seon.cluster.run/plan-digest ?d]])
                            @connection))))
          (testing "the lease was still LIVE — nothing waited it out"
            (is (< elapsed-ms 10000)
                (str "boot+recovery took " elapsed-ms "ms")))
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

(deftest boots-seeded-blocks-are-all-derivable
  (let [root (fresh-root)]
    (try
      (let [instance (cluster/start! {:seon.boot/cluster-name "blocks"
                                      :seon.boot/root root})]
        (try
          (let [db @(:seon.boot/cluster-connection instance)
                agent-id "root"
                relation (into #{}
                               (map first)
                               (d/q '[:find ?block
                                      :in $ ?agent-id
                                      :where
                                      [?agent :seon.cluster.agent/id ?agent-id]
                                      [?agent :seon.cluster.agent/blocks ?block]]
                                    db agent-id))
                derived (block/blocks db agent-id)]
            (testing "the facts carry root's whole seeded set"
              (is (= (count root-render/blocks) (count relation))))
            (testing "the derivation agrees with the facts, one for one"
              (is (= (count relation) (count derived)))
              (is (= (into #{} (map :seon.render.block/name) root-render/blocks)
                     (into #{} (map :seon.render.block/name) derived))))
            (testing "every index answers the same, so no read is privileged"
              (let [agent-eid (:e (first (d/datoms db :avet
                                                   :seon.cluster.agent/id agent-id)))]
                (is (= (count relation)
                       (count (d/datoms db :eavt agent-eid
                                        :seon.cluster.agent/blocks))))
                (is (= relation
                       (set (d/q '[:find [?block ...]
                                   :in $ ?agent-id
                                   :where
                                   [?agent :seon.cluster.agent/id ?agent-id]
                                   [?agent :seon.cluster.agent/blocks ?block]]
                                 db agent-id)))))))
          (finally
            (cluster/stop! instance))))
      (finally
        (delete-recursively! root)))))
