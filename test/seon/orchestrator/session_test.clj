(ns seon.orchestrator.session-test
  "Tests for agent session management.

  Tests session lifecycle, ctx persistence, and recovery functionality.
  Note: nREPL integration tests that require a live pool are in pool_test.clj.
  These tests verify the session layer works correctly without a pool (port=nil)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datalevin.core :as d]
            [seon.orchestrator.session :as session]
            [seon.schema :as schema]
            [seon.test-utils :refer [with-test-node *test-node*]]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(defn cleanup-sessions
  "Fixture that cleans up sessions after each test."
  [f]
  ;; Clear any existing sessions
  (doseq [[id _] @(deref #'seon.orchestrator.session/session-registry)]
    (try
      (session/stop-agent-session! {::session/node *test-node* ::session/id id})
      (catch Exception _)))
  (reset! @#'seon.orchestrator.session/session-registry {})
  (try
    (f)
    (finally
      (doseq [[id _] @(deref #'seon.orchestrator.session/session-registry)]
        (try
          (session/stop-agent-session! {::session/node *test-node* ::session/id id})
          (catch Exception _)))
      (Thread/sleep 50)
      (reset! @#'seon.orchestrator.session/session-registry {}))))

(use-fixtures :each (fn [f]
                      (with-test-node
                        (fn []
                          (cleanup-sessions f)))))

;;; ---------------------------------------------------------------------------
;;; Test Schema Registration (for agent-side validation)
;;; ---------------------------------------------------------------------------

(schema/register! :test.session/value
                  [:int {:min 0 :description "A test integer value"}])

(schema/register! :test.session/name
                  [:string {:min 1 :description "A test string name"}])

;;; ---------------------------------------------------------------------------
;;; Session ID Tests
;;; ---------------------------------------------------------------------------

(deftest session-id-format-test
  (testing "session IDs are 4 hex characters"
    (let [result (session/start-agent-session!
                   {::session/node *test-node*
                    ::session/namespace 'test.id.format})]
      (is (= :running (::session/status result)))
      (is (string? (::session/id result)))
      (is (= 4 (count (::session/id result))))
      (is (re-matches #"[a-f0-9]{4}" (::session/id result))))))

;;; ---------------------------------------------------------------------------
;;; Session Lifecycle Tests
;;; ---------------------------------------------------------------------------

(deftest start-agent-session-test
  (testing "starts a session (no pool = nil port)"
    (let [result (session/start-agent-session!
                   {::session/node *test-node*
                    ::session/namespace 'test.start})]
      (is (= :running (::session/status result)))
      (is (some? (::session/id result)))
      (is (= 'test.start (::session/namespace result)))
      ;; No pool in tests, so port is nil
      (is (nil? (::session/nrepl-port result)))
      (is (inst? (::session/started-at result)))
      (is (= "test.start" (::session/db-name result))))))

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
                    ::session/id "dead"})]
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
                    ::session/id "dead"})]
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
  (testing "returns nil port when no pool (no pool in tests)"
    (let [started (session/start-agent-session!
                    {::session/node *test-node*
                     ::session/namespace 'test.port})
          session-id (::session/id started)
          result (session/get-session-port
                   {::session/node *test-node*
                    ::session/id session-id})]
      (is (nil? (::session/nrepl-port result)))))

  (testing "returns nil port for non-existent session"
    (let [result (session/get-session-port
                   {::session/node *test-node*
                    ::session/id "dead"})]
      (is (nil? (::session/nrepl-port result))))))

;;; ---------------------------------------------------------------------------
;;; Recovery Tests
;;; ---------------------------------------------------------------------------

(deftest recover-sessions-test
  (testing "recover-sessions! marks orphaned sessions as stopped"
    (let [dir (str "tmp/test-recover-" (System/currentTimeMillis))
          dl-schema @#'session/dl-schema
          conn (d/get-conn dir dl-schema)]
      (try
        ;; Insert a "running" session into Datalevin (simulating crash)
        (d/transact! conn [{:orch.session/id "orph"
                             :orch.session/namespace "test.orphan"
                             :orch.session/nrepl-port 7890
                             :orch.session/status "running"
                             :orch.session/started-at (java.util.Date.)
                             :orch.session/db-name "test_orphan"}])

        ;; Temporarily override the private get-dl-conn to return our test conn
        (with-redefs-fn {#'session/get-dl-conn (constantly conn)}
          (fn []
            (let [result (session/recover-sessions!
                           {::session/node *test-node*})]
              (is (= 1 (::session/recovered-count result)))

              ;; Verify status was updated to stopped
              (let [sessions (d/q '[:find (pull ?e [*])
                                    :where [?e :orch.session/id "orph"]]
                                  @conn)
                    entity (ffirst sessions)]
                (is (= "stopped" (:orch.session/status entity)))))))
        (finally
          (d/close conn)
          (let [dir-file (java.io.File. dir)]
            (doseq [f (reverse (file-seq dir-file))]
              (.delete f))))))))

;;; ---------------------------------------------------------------------------
;;; Activity Tracking Tests (Phase 4c)
;;; ---------------------------------------------------------------------------

(deftest activity-tracking-test
  (testing "sessions track eval activity"
    (let [started (session/start-agent-session!
                    {::session/node *test-node*
                     ::session/namespace 'test.activity})
          session-id (::session/id started)]

      ;; Initial state: 0 evals, no current eval
      (let [info (session/get-agent-session
                   {::session/node *test-node*
                    ::session/id session-id})]
        (is (= 0 (::session/eval-count info)))
        (is (nil? (::session/current-eval info))))

      ;; Record start of an eval
      (session/record-eval-start!
        {::session/id session-id
         ::session/code "(+ 1 2)"})

      ;; Should have current-eval set
      (let [info (session/get-agent-session
                   {::session/node *test-node*
                    ::session/id session-id})]
        (is (some? (::session/current-eval info)))
        (is (= "(+ 1 2)" (::session/code (::session/current-eval info)))))

      ;; Record completion
      (session/record-eval-complete!
        {::session/id session-id})

      ;; Should have incremented count and cleared current-eval
      (let [info (session/get-agent-session
                   {::session/node *test-node*
                    ::session/id session-id})]
        (is (= 1 (::session/eval-count info)))
        (is (nil? (::session/current-eval info)))
        (is (some? (::session/last-activity-at info)))))))

(deftest activity-tracking-nonexistent-session-test
  (testing "activity tracking returns false for non-existent sessions"
    (let [start-result (session/record-eval-start!
                         {::session/id "dead"
                          ::session/code "(+ 1 2)"})
          complete-result (session/record-eval-complete!
                            {::session/id "dead"})]
      (is (false? (::session/recorded start-result)))
      (is (false? (::session/recorded complete-result))))))

(deftest list-sessions-includes-observability-test
  (testing "list-agent-sessions includes observability fields"
    (let [started (session/start-agent-session!
                    {::session/node *test-node*
                     ::session/namespace 'test.obs})
          session-id (::session/id started)]

      ;; Do some evals
      (session/record-eval-start! {::session/id session-id ::session/code "(+ 1 2)"})
      (session/record-eval-complete! {::session/id session-id})

      (let [sessions (session/list-agent-sessions
                       {::session/node *test-node*})
            session (first (filter #(= session-id (::session/id %)) sessions))]
        (is (some? session))
        (is (= 1 (::session/eval-count session)))
        (is (some? (::session/last-activity-at session)))
        (is (nil? (::session/current-eval session)))))))

;;; ---------------------------------------------------------------------------
;;; nREPL Session ID Tests
;;; ---------------------------------------------------------------------------

(deftest set-nrepl-session-id-test
  (testing "can set nREPL session ID for existing session"
    (let [started (session/start-agent-session!
                    {::session/node *test-node*
                     ::session/namespace 'test.nrepl.sid})
          session-id (::session/id started)
          nrepl-sid "test-nrepl-session-123"]

      ;; Set the nREPL session ID
      (let [result (session/set-nrepl-session-id!
                     {::session/id session-id
                      ::session/nrepl-session-id nrepl-sid})]
        (is (true? (::session/set result))))

      ;; Verify it's stored
      (let [info (session/get-session-port
                   {::session/node *test-node*
                    ::session/id session-id})]
        (is (= nrepl-sid (::session/nrepl-session-id info))))))

  (testing "returns false for non-existent session"
    (let [result (session/set-nrepl-session-id!
                   {::session/id "dead"
                    ::session/nrepl-session-id "test-123"})]
      (is (false? (::session/set result))))))

(deftest get-session-port-includes-nrepl-session-id-test
  (testing "get-session-port returns nrepl-session-id when set"
    (let [started (session/start-agent-session!
                    {::session/node *test-node*
                     ::session/namespace 'test.port.sid})
          session-id (::session/id started)
          nrepl-sid "my-nrepl-session-456"]

      ;; Initially nil
      (let [info (session/get-session-port
                   {::session/node *test-node*
                    ::session/id session-id})]
        (is (nil? (::session/nrepl-session-id info))))

      ;; Set it
      (session/set-nrepl-session-id!
        {::session/id session-id
         ::session/nrepl-session-id nrepl-sid})

      ;; Now returned
      (let [info (session/get-session-port
                   {::session/node *test-node*
                    ::session/id session-id})]
        (is (= nrepl-sid (::session/nrepl-session-id info)))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.orchestrator.session-test)

  ;; Run specific test
  (clojure.test/test-var #'start-agent-session-test)
  (clojure.test/test-var #'activity-tracking-test))
