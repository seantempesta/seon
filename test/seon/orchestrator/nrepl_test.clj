(ns seon.orchestrator.nrepl-test
  "Tests for multi-nREPL server management.

  Tests the namespace nREPL isolation, port allocation, context injection,
  and server lifecycle."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [seon.orchestrator.nrepl :as nrepl-multi]
            [nrepl.core :as nrepl]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(defn cleanup-servers
  "Fixture that cleans up any running servers after each test.

   CRITICAL: Must stop servers BEFORE resetting registries, otherwise
   we lose references to the servers and can't close their sockets."
  [f]
  ;; BEFORE test: Stop servers first, THEN reset registries
  (nrepl-multi/stop-all-namespace-nrepls!)
  (Thread/sleep 20)
  (reset! @#'seon.orchestrator.nrepl/port-registry {})
  (reset! @#'seon.orchestrator.nrepl/servers {})
  ;; Close all existing nREPL sessions
  (require 'nrepl.middleware.session)
  (let [sessions-atom @(resolve 'nrepl.middleware.session/sessions)
        close-session (resolve 'nrepl.middleware.session/close-session)]
    (doseq [[_ session] @sessions-atom]
      (try (close-session session) (catch Exception _)))
    (reset! sessions-atom {}))
  (try
    (f)
    (finally
      ;; AFTER test: Stop servers first, THEN reset registries
      (nrepl-multi/stop-all-namespace-nrepls!)
      (Thread/sleep 20)
      (reset! @#'seon.orchestrator.nrepl/port-registry {})
      (reset! @#'seon.orchestrator.nrepl/servers {})
      ;; Close all sessions
      (let [sessions-atom @(resolve 'nrepl.middleware.session/sessions)]
        (doseq [[_ session] @sessions-atom]
          (try
            ((resolve 'nrepl.middleware.session/close-session) session)
            (catch Exception _)))
        (reset! sessions-atom {})))))

(use-fixtures :each cleanup-servers)

;;; ---------------------------------------------------------------------------
;;; Port Management Tests
;;; ---------------------------------------------------------------------------

(deftest allocate-port-test
  (testing "allocates ports starting from base port"
    (let [port1 (nrepl-multi/allocate-port! 'test.ns1)]
      (is (>= port1 7889) "First port should be >= 7889")))

  (testing "returns same port for same namespace"
    (let [port1 (nrepl-multi/allocate-port! 'test.ns2)
          port2 (nrepl-multi/allocate-port! 'test.ns2)]
      (is (= port1 port2) "Same namespace should get same port")))

  (testing "allocates different ports for different namespaces"
    (let [port1 (nrepl-multi/allocate-port! 'test.ns3)
          port2 (nrepl-multi/allocate-port! 'test.ns4)]
      (is (not= port1 port2) "Different namespaces should get different ports"))))

(deftest release-port-test
  (testing "releases allocated port"
    (let [port (nrepl-multi/allocate-port! 'test.release)]
      (is (= port (nrepl-multi/get-allocated-port 'test.release)))
      (nrepl-multi/release-port! 'test.release)
      (is (nil? (nrepl-multi/get-allocated-port 'test.release)))))

  (testing "release is idempotent"
    (nrepl-multi/release-port! 'test.nonexistent)
    ;; Should not throw
    ))

(deftest get-allocated-port-test
  (testing "returns nil for unallocated namespace"
    (is (nil? (nrepl-multi/get-allocated-port 'test.unallocated))))

  (testing "returns port for allocated namespace"
    (let [port (nrepl-multi/allocate-port! 'test.allocated)]
      (is (= port (nrepl-multi/get-allocated-port 'test.allocated))))))

;;; ---------------------------------------------------------------------------
;;; Server Lifecycle Tests
;;; ---------------------------------------------------------------------------

(deftest start-namespace-nrepl-test
  (testing "starts an nREPL server for a namespace"
    (let [result (nrepl-multi/start-namespace-nrepl! {:namespace 'test.start})]
      (is (= :running (:status result)) "Status should be :running")
      (is (some? (:server result)) "Should have server object")
      (is (some? (:port result)) "Should have port")
      (is (some? (:ctx result)) "Should have ctx atom")
      (is (= 'test.start (:namespace result)) "Should have namespace")))

  (testing "ctx contains expected keys"
    (let [{:keys [ctx]} (nrepl-multi/start-namespace-nrepl! {:namespace 'test.ctx-keys})]
      (is (= 'test.ctx-keys (:seon.agent/namespace @ctx)))
      (is (some? (:seon.agent/started-at @ctx)))
      (is (some? (:seon.agent/nrepl-port @ctx)))))

  (testing "ctx includes optional values when provided"
    (let [test-db {:type :test-db}
          test-fn (fn [x] x)
          {:keys [ctx]} (nrepl-multi/start-namespace-nrepl!
                         {:namespace 'test.ctx-optional
                          :db test-db
                          :render-fn test-fn
                          :worktree "/path/to/worktree"})]
      (is (= test-db (:seon.agent/db @ctx)))
      (is (= test-fn (:seon.agent/render-fn @ctx)))
      (is (= "/path/to/worktree" (:seon.agent/worktree @ctx)))))

  (testing "throws when namespace is missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"namespace is required"
          (nrepl-multi/start-namespace-nrepl! {}))))

  (testing "throws when namespace already has server"
    (nrepl-multi/start-namespace-nrepl! {:namespace 'test.duplicate})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already has an nREPL server"
          (nrepl-multi/start-namespace-nrepl! {:namespace 'test.duplicate})))))

(deftest stop-namespace-nrepl-test
  (testing "stops a running server"
    (nrepl-multi/start-namespace-nrepl! {:namespace 'test.stop})
    (is (nrepl-multi/namespace-server-running? 'test.stop))

    (let [result (nrepl-multi/stop-namespace-nrepl! 'test.stop)]
      (is (= :stopped (:status result)))
      (is (= 'test.stop (:namespace result)))
      (is (not (nrepl-multi/namespace-server-running? 'test.stop)))))

  (testing "returns nil for non-running namespace"
    (is (nil? (nrepl-multi/stop-namespace-nrepl! 'test.not-running)))))

(deftest stop-all-namespace-nrepls-test
  (testing "stops all running servers"
    (nrepl-multi/start-namespace-nrepl! {:namespace 'test.all1})
    (nrepl-multi/start-namespace-nrepl! {:namespace 'test.all2})
    (nrepl-multi/start-namespace-nrepl! {:namespace 'test.all3})

    (is (= 3 (count (nrepl-multi/list-namespace-servers))))

    (nrepl-multi/stop-all-namespace-nrepls!)

    (is (= 0 (count (nrepl-multi/list-namespace-servers))))))

;;; ---------------------------------------------------------------------------
;;; Server Query Tests
;;; ---------------------------------------------------------------------------

(deftest namespace-server-running-test
  (testing "returns false for non-running namespace"
    (is (false? (nrepl-multi/namespace-server-running? 'test.not-running))))

  (testing "returns true for running namespace"
    (nrepl-multi/start-namespace-nrepl! {:namespace 'test.running})
    (is (true? (nrepl-multi/namespace-server-running? 'test.running)))))

(deftest list-namespace-servers-test
  (testing "returns empty list when no servers running"
    (is (empty? (nrepl-multi/list-namespace-servers))))

  (testing "lists all running servers"
    (nrepl-multi/start-namespace-nrepl! {:namespace 'test.list1})
    (nrepl-multi/start-namespace-nrepl! {:namespace 'test.list2})

    (let [servers (nrepl-multi/list-namespace-servers)]
      (is (= 2 (count servers)))
      (is (every? #(contains? % :namespace) servers))
      (is (every? #(contains? % :port) servers))
      (is (every? #(contains? % :status) servers))
      (is (every? #(contains? % :started-at) servers)))))

(deftest get-namespace-server-test
  (testing "returns nil for non-running namespace"
    (is (nil? (nrepl-multi/get-namespace-server 'test.not-running))))

  (testing "returns server info for running namespace"
    (let [started (nrepl-multi/start-namespace-nrepl! {:namespace 'test.get-server})
          retrieved (nrepl-multi/get-namespace-server 'test.get-server)]
      (is (= (:server started) (:server retrieved)))
      (is (= (:port started) (:port retrieved)))
      (is (= (:ctx started) (:ctx retrieved))))))

(deftest get-namespace-ctx-test
  (testing "returns nil for non-running namespace"
    (is (nil? (nrepl-multi/get-namespace-ctx 'test.not-running))))

  (testing "returns ctx atom for running namespace"
    (let [{:keys [ctx]} (nrepl-multi/start-namespace-nrepl! {:namespace 'test.get-ctx})]
      (is (= ctx (nrepl-multi/get-namespace-ctx 'test.get-ctx))))))

;;; ---------------------------------------------------------------------------
;;; nREPL Client Integration Tests
;;; ---------------------------------------------------------------------------

(defn- clone-session
  "Clone a session to get a persistent session with injected bindings."
  [client]
  (let [resp (doall (nrepl/message client {:op "clone"}))]
    (:new-session (first (filter :new-session resp)))))

(defn- eval-in-session
  "Evaluate code in a specific session and return the values."
  [client session code]
  (let [resp (doall (nrepl/message client {:op "eval" :code code :session session}))
        values (keep :value resp)]
    values))

(deftest nrepl-connection-test
  (testing "can connect and evaluate code on namespace nREPL"
    (let [{:keys [port]} (nrepl-multi/start-namespace-nrepl! {:namespace 'test.connect})]
      (with-open [conn (nrepl/connect :port port)]
        (let [client (nrepl/client conn 5000)
              session (clone-session client)
              values (eval-in-session client session "(+ 1 2)")]
          (is (some #(= "3" %) values) "Should evaluate to 3"))))))

(deftest nrepl-ctx-injection-test
  (testing "*ctx* is available in nREPL sessions without qualification"
    (let [{:keys [port]} (nrepl-multi/start-namespace-nrepl!
                          {:namespace 'test.ctx-inject
                           :db {:type :test}})]
      (with-open [conn (nrepl/connect :port port)]
        (let [client (nrepl/client conn 5000)
              session (clone-session client)
              ;; *ctx* should be available directly in the namespace - no require needed!
              values (eval-in-session client session "(:seon.agent/namespace @*ctx*)")]
          (is (some #(= "test.ctx-inject" %) values)
              "*ctx* should be available without qualification"))))))

(deftest nrepl-ns-binding-test
  (testing "*ns* is bound to target namespace in nREPL sessions"
    (let [{:keys [port]} (nrepl-multi/start-namespace-nrepl! {:namespace 'test.ns-bind})]
      (with-open [conn (nrepl/connect :port port)]
        (let [client (nrepl/client conn 5000)
              session (clone-session client)
              values (eval-in-session client session "(ns-name *ns*)")]
          (is (some #(= "test.ns-bind" %) values)
              "*ns* should be the target namespace"))))))

;;; ---------------------------------------------------------------------------
;;; Multiple Server Isolation Tests
;;; ---------------------------------------------------------------------------

(deftest multiple-servers-isolation-test
  (testing "multiple servers run independently on different ports"
    (let [server1 (nrepl-multi/start-namespace-nrepl! {:namespace 'test.iso1})
          server2 (nrepl-multi/start-namespace-nrepl! {:namespace 'test.iso2})]
      (is (not= (:port server1) (:port server2))
          "Servers should be on different ports")

      ;; Connect to server1 first, verify, then disconnect
      (let [values1 (with-open [conn1 (nrepl/connect :port (:port server1))]
                      (let [client1 (nrepl/client conn1 5000)
                            session1 (clone-session client1)]
                        (eval-in-session client1 session1 "(ns-name *ns*)")))]
        (is (some #(= "test.iso1" %) values1) "Server1 should have iso1 namespace"))

      ;; Now connect to server2, verify
      (let [values2 (with-open [conn2 (nrepl/connect :port (:port server2))]
                      (let [client2 (nrepl/client conn2 5000)
                            session2 (clone-session client2)]
                        (eval-in-session client2 session2 "(ns-name *ns*)")))]
        (is (some #(= "test.iso2" %) values2) "Server2 should have iso2 namespace")))))

(deftest ctx-isolation-test
  (testing "each server has its own independent ctx"
    (let [server1 (nrepl-multi/start-namespace-nrepl! {:namespace 'test.ctx1})
          server2 (nrepl-multi/start-namespace-nrepl! {:namespace 'test.ctx2})]

      ;; Modify ctx1
      (swap! (:ctx server1) assoc :custom-key "value1")

      ;; ctx2 should not be affected
      (is (nil? (:custom-key @(:ctx server2))))
      (is (= "value1" (:custom-key @(:ctx server1)))))))

;;; ---------------------------------------------------------------------------
;;; Port Conflict Handling Tests
;;; ---------------------------------------------------------------------------

(deftest port-conflict-test
  (testing "handles port conflict gracefully"
    ;; Start first server on a specific port
    (let [port 7950
          server1 (nrepl-multi/start-namespace-nrepl! {:namespace 'test.conflict1
                                                        :port port})]
      (is (= :running (:status server1)))
      (is (= port (:port server1)))

      ;; Try to start second server on same port (different namespace)
      ;; This should return a port-conflict status, not throw
      (let [result (try
                     (nrepl-multi/start-namespace-nrepl! {:namespace 'test.conflict2
                                                          :port port})
                     (catch clojure.lang.ExceptionInfo e
                       ;; This could also be an exception for duplicate namespace check
                       {:status :exception :error (.getMessage e)}))]
        (is (= :port-conflict (:status result))
            "Should return port-conflict status")))))

;;; ---------------------------------------------------------------------------
;;; Stress/Concurrency Tests
;;; ---------------------------------------------------------------------------

(deftest concurrent-server-starts-test
  (testing "can start multiple servers concurrently"
    (let [namespaces (mapv #(symbol (str "test.concurrent" %)) (range 5))
          futures (doall (map #(future (nrepl-multi/start-namespace-nrepl! {:namespace %}))
                              namespaces))]
      ;; Wait for all to complete
      (let [results (doall (map deref futures))]
        ;; All should succeed
        (is (every? #(= :running (:status %)) results)
            "All servers should start successfully")

        ;; All should have different ports
        (let [ports (map :port results)]
          (is (= (count ports) (count (set ports)))
              "All ports should be unique"))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.orchestrator.nrepl-test)

  ;; Run specific test
  (clojure.test/test-var #'start-namespace-nrepl-test)
  (clojure.test/test-var #'nrepl-connection-test)
  (clojure.test/test-var #'multiple-servers-isolation-test))
