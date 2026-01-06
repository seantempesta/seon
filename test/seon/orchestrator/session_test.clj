(ns seon.orchestrator.session-test
  "Tests for agent session management.

  Tests session lifecycle, ctx persistence, nREPL integration,
  and recovery functionality."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [nrepl.core :as nrepl]
            [seon.orchestrator.session :as session]
            [seon.orchestrator.nrepl :as nrepl-multi]
            [seon.schema :as schema]
            [seon.test-utils :refer [with-test-node *test-node*]]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(defn cleanup-sessions
  "Fixture that cleans up sessions and nREPL servers after each test.

   IMPORTANT: Must stop servers BEFORE resetting registries, otherwise
   we lose track of the server objects and can't close their sockets."
  [f]
  ;; BEFORE test: Stop any existing servers FIRST, then reset registries
  ;; This order is critical - resetting first creates zombie sockets
  (nrepl-multi/stop-all-namespace-nrepls!)
  (doseq [[id _] @(deref #'seon.orchestrator.session/session-registry)]
    (try
      (session/stop-agent-session! {::session/node *test-node* ::session/id id})
      (catch Exception _)))
  ;; Now safe to reset registries
  (reset! @#'seon.orchestrator.session/session-registry {})
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
      ;; AFTER test: Same order - stop servers first, then reset
      (nrepl-multi/stop-all-namespace-nrepls!)
      (doseq [[id _] @(deref #'seon.orchestrator.session/session-registry)]
        (try
          (session/stop-agent-session! {::session/node *test-node* ::session/id id})
          (catch Exception _)))
      ;; Give threads time to terminate
      (Thread/sleep 50)
      ;; Now safe to reset registries
      (reset! @#'seon.orchestrator.session/session-registry {})
      (reset! @#'seon.orchestrator.nrepl/port-registry {})
      (reset! @#'seon.orchestrator.nrepl/servers {})
      ;; Close all sessions
      (let [sessions-atom @(resolve 'nrepl.middleware.session/sessions)]
        (doseq [[_ session] @sessions-atom]
          (try
            ((resolve 'nrepl.middleware.session/close-session) session)
            (catch Exception _)))
        (reset! sessions-atom {})))))

(use-fixtures :each (fn [f]
                      (with-test-node
                        (fn []
                          (cleanup-sessions f)))))

;;; ---------------------------------------------------------------------------
;;; Test Schema Registration (for agent-side validation)
;;; ---------------------------------------------------------------------------

;; Register test schemas that agents would use
(schema/register! :test.session/value
                  [:int {:min 0 :description "A test integer value"}])

(schema/register! :test.session/name
                  [:string {:min 1 :description "A test string name"}])

;;; ---------------------------------------------------------------------------
;;; Session ID Tests
;;; ---------------------------------------------------------------------------

(deftest session-id-format-test
  (testing "session IDs are 8 hex characters"
    (let [result (session/start-agent-session!
                   {::session/node *test-node*
                    ::session/namespace 'test.id.format})]
      (is (= :running (::session/status result)))
      (is (string? (::session/id result)))
      (is (= 8 (count (::session/id result))))
      (is (re-matches #"[a-f0-9]{8}" (::session/id result))))))

;;; ---------------------------------------------------------------------------
;;; Session Lifecycle Tests
;;; ---------------------------------------------------------------------------

(deftest start-agent-session-test
  (testing "starts a session with all components"
    (let [result (session/start-agent-session!
                   {::session/node *test-node*
                    ::session/namespace 'test.start})]
      (is (= :running (::session/status result)))
      (is (some? (::session/id result)))
      (is (= 'test.start (::session/namespace result)))
      (is (integer? (::session/nrepl-port result)))
      (is (>= (::session/nrepl-port result) 7889))
      (is (inst? (::session/started-at result)))
      (is (= "test_start" (::session/db-name result))))))

(deftest stop-agent-session-test
  (testing "stops a running session"
    (let [started (session/start-agent-session!
                    {::session/node *test-node*
                     ::session/namespace 'test.stop})
          session-id (::session/id started)]
      (is (= :running (::session/status started)))

      (let [stopped (session/stop-agent-session!
                      {::session/node *test-node*
                       ::session/id session-id})]
        (is (= :stopped (::session/status stopped)))
        (is (= session-id (::session/id stopped)))
        (is (inst? (::session/stopped-at stopped))))))

  (testing "returns error for non-existent session"
    (let [result (session/stop-agent-session!
                   {::session/node *test-node*
                    ::session/id "deadbeef"})]
      (is (= :error (::session/status result)))
      (is (= "Session not found" (::session/error result))))))

;;; ---------------------------------------------------------------------------
;;; Session Query Tests
;;; ---------------------------------------------------------------------------

(deftest get-agent-session-test
  (testing "returns session info for running session"
    (let [started (session/start-agent-session!
                    {::session/node *test-node*
                     ::session/namespace 'test.get})
          session-id (::session/id started)
          retrieved (session/get-agent-session
                      {::session/node *test-node*
                       ::session/id session-id})]
      (is (= session-id (::session/id retrieved)))
      (is (= 'test.get (::session/namespace retrieved)))
      (is (= :running (::session/status retrieved)))))

  (testing "returns empty map for non-existent session"
    (let [result (session/get-agent-session
                   {::session/node *test-node*
                    ::session/id "deadbeef"})]
      (is (= {} result)))))

(deftest list-agent-sessions-test
  (testing "returns empty list when no sessions"
    (is (empty? (session/list-agent-sessions
                  {::session/node *test-node*}))))

  (testing "lists all active sessions"
    (session/start-agent-session!
      {::session/node *test-node*
       ::session/namespace 'test.list1})
    (session/start-agent-session!
      {::session/node *test-node*
       ::session/namespace 'test.list2})

    (let [sessions (session/list-agent-sessions
                     {::session/node *test-node*})]
      (is (= 2 (count sessions)))
      (is (every? #(contains? % ::session/id) sessions))
      (is (every? #(contains? % ::session/namespace) sessions))
      (is (every? #(= :running (::session/status %)) sessions)))))

(deftest get-session-port-test
  (testing "returns port for running session"
    (let [started (session/start-agent-session!
                    {::session/node *test-node*
                     ::session/namespace 'test.port})
          session-id (::session/id started)
          result (session/get-session-port
                   {::session/node *test-node*
                    ::session/id session-id})]
      (is (= (::session/nrepl-port started)
             (::session/nrepl-port result)))))

  (testing "returns nil port for non-existent session"
    (let [result (session/get-session-port
                   {::session/node *test-node*
                    ::session/id "deadbeef"})]
      (is (nil? (::session/nrepl-port result))))))

;;; ---------------------------------------------------------------------------
;;; nREPL Integration Tests
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

(deftest session-nrepl-connection-test
  (testing "can connect and eval via session's nREPL"
    (let [started (session/start-agent-session!
                    {::session/node *test-node*
                     ::session/namespace 'test.nrepl.connect})
          port (::session/nrepl-port started)]
      (with-open [conn (nrepl/connect :port port)]
        (let [client (nrepl/client conn 5000)
              session (clone-session client)
              values (eval-in-session client session "(+ 1 2 3)")]
          (is (some #(= "6" %) values)))))))

(deftest session-ctx-available-test
  (testing "*ctx* is available in session's nREPL"
    (let [started (session/start-agent-session!
                    {::session/node *test-node*
                     ::session/namespace 'test.ctx.avail})
          port (::session/nrepl-port started)]
      (with-open [conn (nrepl/connect :port port)]
        (let [client (nrepl/client conn 5000)
              session (clone-session client)
              _ (eval-in-session client session "(require 'seon.orchestrator.nrepl)")
              values (eval-in-session client session
                       "(-> seon.orchestrator.nrepl/*ctx* deref :seon.agent/namespace)")]
          (is (some #(= "test.ctx.avail" %) values)))))))

(deftest session-ns-bound-test
  (testing "*ns* is bound to session namespace"
    (let [started (session/start-agent-session!
                    {::session/node *test-node*
                     ::session/namespace 'test.ns.bound})
          port (::session/nrepl-port started)]
      (with-open [conn (nrepl/connect :port port)]
        (let [client (nrepl/client conn 5000)
              session (clone-session client)
              values (eval-in-session client session "(ns-name *ns*)")]
          (is (some #(= "test.ns.bound" %) values)))))))

;;; ---------------------------------------------------------------------------
;;; Session Resume Tests
;;; ---------------------------------------------------------------------------

(deftest session-resume-ctx-state-test
  (testing "resumed session loads previous ctx state"
    ;; Start first session and add some state
    (let [started1 (session/start-agent-session!
                     {::session/node *test-node*
                      ::session/namespace 'test.resume})
          port1 (::session/nrepl-port started1)
          session-id1 (::session/id started1)]

      ;; Add state via nREPL
      (with-open [conn (nrepl/connect :port port1)]
        (let [client (nrepl/client conn 5000)
              session (clone-session client)]
          ;; Add a value to ctx
          (eval-in-session client session
            "(require 'seon.orchestrator.nrepl)")
          (eval-in-session client session
            "(swap! seon.orchestrator.nrepl/*ctx* assoc :test.session/value 42)")
          ;; Wait for debounce
          (Thread/sleep 200)))

      ;; Stop the session (flushes ctx)
      (session/stop-agent-session!
        {::session/node *test-node*
         ::session/id session-id1})

      ;; Wait for async operations
      (Thread/sleep 200)

      ;; Start new session with resume (should load ctx)
      (let [started2 (session/start-agent-session!
                       {::session/node *test-node*
                        ::session/namespace 'test.resume
                        ::session/resume? true})
            port2 (::session/nrepl-port started2)]

        ;; Verify the state was restored
        (with-open [conn (nrepl/connect :port port2)]
          (let [client (nrepl/client conn 5000)
                session (clone-session client)
                _ (eval-in-session client session
                    "(require 'seon.orchestrator.nrepl)")
                values (eval-in-session client session
                         "(:test.session/value @seon.orchestrator.nrepl/*ctx*)")]
            (is (some #(= "42" %) values)
                "Resumed session should have the persisted ctx state")))))))

;;; ---------------------------------------------------------------------------
;;; Multiple Sessions Test
;;; ---------------------------------------------------------------------------

(deftest multiple-sessions-isolation-test
  (testing "multiple sessions have isolated ctx"
    (let [session1 (session/start-agent-session!
                     {::session/node *test-node*
                      ::session/namespace 'test.iso1})
          session2 (session/start-agent-session!
                     {::session/node *test-node*
                      ::session/namespace 'test.iso2})
          port1 (::session/nrepl-port session1)
          port2 (::session/nrepl-port session2)]

      ;; Verify different ports
      (is (not= port1 port2))

      ;; Modify ctx on session1 - use its own namespace for the key
      (with-open [conn1 (nrepl/connect :port port1)]
        (let [client1 (nrepl/client conn1 5000)
              nrepl-session1 (clone-session client1)]
          (eval-in-session client1 nrepl-session1
            "(require 'seon.orchestrator.nrepl)")
          ;; Use a schema we've registered
          (eval-in-session client1 nrepl-session1
            "(swap! seon.orchestrator.nrepl/*ctx* assoc :test.session/name \"session1\")")))

      ;; session2 should not have that key
      (with-open [conn2 (nrepl/connect :port port2)]
        (let [client2 (nrepl/client conn2 5000)
              nrepl-session2 (clone-session client2)
              _ (eval-in-session client2 nrepl-session2
                  "(require 'seon.orchestrator.nrepl)")
              values (eval-in-session client2 nrepl-session2
                       "(:test.session/name @seon.orchestrator.nrepl/*ctx*)")]
          (is (some #(= "nil" %) values)
              "Session 2 should not have session 1's ctx values"))))))

;;; ---------------------------------------------------------------------------
;;; Recovery Tests
;;; ---------------------------------------------------------------------------

(deftest recover-sessions-test
  (testing "recover-sessions! marks orphaned sessions as stopped"
    ;; Manually insert a "running" session into XTDB (simulating crash)
    (require '[xtdb.api :as xt])
    (let [xt-exec (resolve 'xt/execute-tx)]
      (xt-exec *test-node*
               [[:put-docs :sessions
                 {:xt/id "session-orphan123"
                  :session/id "orphan12"
                  :session/namespace "test.orphan"
                  :session/nrepl-port 7890
                  :session/status "running"
                  :session/started-at (java.util.Date.)
                  :session/db-name "test_orphan"}]]))

    ;; Run recovery
    (let [result (session/recover-sessions!
                   {::session/node *test-node*})]
      (is (= 1 (::session/recovered-count result))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.orchestrator.session-test)

  ;; Run specific test
  (clojure.test/test-var #'start-agent-session-test)
  (clojure.test/test-var #'session-resume-ctx-state-test))
