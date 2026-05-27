(ns seon.session-test
  "Tests for agent session management.

  Tests session lifecycle, ctx persistence, and recovery functionality.
  Note: nREPL integration tests that require a live pool are in pool_test.clj.
  These tests verify the session layer works correctly without a pool (port=nil).

  Uses `tu/with-test-db-fixture` to route `:seon.orchestrator` writes/queries
  through an isolated datahike `:memory` flow per test — keeps the live
  orchestrator DB clean. See `docs/prds/datahike-migration/test-fixture-design.md`."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [seon.session :as session]
            [seon.runtime :as runtime]
            [seon.schema :as schema]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(defn- reset-process-state
  "Reset live-state + runtime registry between tests. Per-test DB isolation
   is handled by `with-test-db-fixture`; this resets the JVM-process state
   that lives outside the datahike DB."
  [f]
  (runtime/reset-registry! {})
  (reset! @#'seon.session/live-state {})
  (try
    (f)
    (finally
      (reset! @#'seon.session/live-state {})
      (runtime/reset-registry! {}))))

(use-fixtures :each
  (tu/with-test-db-fixture
    {::tu/namespaces [:seon.orchestrator]
     ::tu/schemas    {:seon.orchestrator session/session-entity-schema}})
  reset-process-state)

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
  (testing "session IDs are 6 hex characters"
    (let [result (session/start-agent-session!
                   {::session/namespace 'test.id.format
                    ::session/pool nil})]
      (is (= :running (::session/status result)))
      (is (string? (::session/id result)))
      (is (= 6 (count (::session/id result))))
      (is (re-matches #"[A-Za-z0-9]{6}" (::session/id result))))))

;;; ---------------------------------------------------------------------------
;;; Session Lifecycle Tests
;;; ---------------------------------------------------------------------------

(deftest start-agent-session-test
  (testing "starts a session (no pool = nil port)"
    (let [result (session/start-agent-session!
                   {::session/namespace 'test.start
                    ::session/pool nil})]
      (is (= :running (::session/status result)))
      (is (some? (::session/id result)))
      (is (= "test.start" (::session/namespace result)))
      ;; No pool in tests, so port is nil
      (is (nil? (::session/nrepl-port result)))
      (is (inst? (::session/started-at result)))
      (is (= "test.start" (::session/db-name result))))))

(deftest stop-agent-session-test
  (testing "stops a running session"
    (let [started (session/start-agent-session!
                    {::session/namespace 'test.stop
                     ::session/pool nil})
          session-id (::session/id started)]
      (is (= :running (::session/status started)))

      (let [stopped (session/stop-agent-session!
                      {::session/id session-id})]
        (is (= :stopped (::session/status stopped)))
        (is (= session-id (::session/id stopped)))
        (is (inst? (::session/stopped-at stopped))))))

  (testing "returns error for non-existent session"
    (let [result (session/stop-agent-session!
                   {::session/id "dead00"})]
      (is (= :error (::session/status result)))
      (is (= "Session not found" (::session/error result))))))

;;; ---------------------------------------------------------------------------
;;; Session Query Tests
;;; ---------------------------------------------------------------------------

(deftest get-agent-session-test
  (testing "returns session info for running session"
    (let [started (session/start-agent-session!
                    {::session/namespace 'test.get
                     ::session/pool nil})
          session-id (::session/id started)
          retrieved (session/get-agent-session
                      {::session/id session-id})]
      (is (= session-id (::session/id retrieved)))
      (is (= "test.get" (::session/namespace retrieved)))
      (is (= :running (::session/status retrieved)))))

  (testing "returns empty map for non-existent session"
    (let [result (session/get-agent-session
                   {::session/id "dead00"})]
      (is (= {} result)))))

(deftest list-agent-sessions-test
  (testing "returns empty list when no sessions"
    (is (empty? (session/list-agent-sessions {}))))

  (testing "lists all active sessions"
    (session/start-agent-session!
      {::session/namespace 'test.list1
       ::session/pool nil})
    (session/start-agent-session!
      {::session/namespace 'test.list2
       ::session/pool nil})

    (let [sessions (session/list-agent-sessions {})]
      (is (= 2 (count sessions)))
      (is (every? #(contains? % ::session/id) sessions))
      (is (every? #(contains? % ::session/namespace) sessions))
      (is (every? #(= :running (::session/status %)) sessions)))))

(deftest get-session-port-test
  (testing "returns nil port when no pool (no pool in tests)"
    (let [started (session/start-agent-session!
                    {::session/namespace 'test.port
                     ::session/pool nil})
          session-id (::session/id started)
          result (session/get-session-port
                   {::session/id session-id})]
      (is (nil? (::session/nrepl-port result)))))

  (testing "returns nil port for non-existent session"
    (let [result (session/get-session-port
                   {::session/id "dead00"})]
      (is (nil? (::session/nrepl-port result))))))

;;; ---------------------------------------------------------------------------
;;; Recovery Tests
;;; ---------------------------------------------------------------------------

(deftest recover-sessions-test
  (testing "recover-sessions! marks orphaned external running instances as stopped"
    ;; Register an external running instance in the runtime registry
    ;; (simulating a session that was running before crash)
    (runtime/register! {::runtime/namespace "test.orphan"
                        ::runtime/status :running
                        ::runtime/location :external
                        ::runtime/session-id "orph00"})

    (let [result (session/recover-sessions! {})]
      (is (= 1 (::session/recovered-count result)))

      ;; Verify status was updated to stopped in runtime registry
      (let [inst (runtime/instance {::runtime/namespace "test.orphan"})]
        (is (= :stopped (::runtime/status inst)))))))

;;; ---------------------------------------------------------------------------
;;; Activity Tracking Tests (Phase 4c)
;;; ---------------------------------------------------------------------------

(deftest activity-tracking-test
  (testing "sessions track eval activity"
    (let [started (session/start-agent-session!
                    {::session/namespace 'test.activity
                     ::session/pool nil})
          session-id (::session/id started)]

      ;; Initial state: 0 evals, no current eval
      (let [info (session/get-agent-session
                   {::session/id session-id})]
        (is (= 0 (::session/eval-count info)))
        (is (nil? (::session/current-eval info))))

      ;; Record start of an eval
      (session/record-eval-start!
        {::session/id session-id
         ::session/code "(+ 1 2)"})

      ;; Should have current-eval set
      (let [info (session/get-agent-session
                   {::session/id session-id})]
        (is (some? (::session/current-eval info)))
        (is (= "(+ 1 2)" (::session/code (::session/current-eval info)))))

      ;; Record completion
      (session/record-eval-complete!
        {::session/id session-id})

      ;; Should have incremented count and cleared current-eval
      (let [info (session/get-agent-session
                   {::session/id session-id})]
        (is (= 1 (::session/eval-count info)))
        (is (nil? (::session/current-eval info)))
        (is (some? (::session/last-activity-at info)))))))

(deftest activity-tracking-nonexistent-session-test
  (testing "activity tracking returns false for non-existent sessions"
    (let [start-result (session/record-eval-start!
                         {::session/id "dead00"
                          ::session/code "(+ 1 2)"})
          complete-result (session/record-eval-complete!
                            {::session/id "dead00"})]
      (is (false? (::session/recorded start-result)))
      (is (false? (::session/recorded complete-result))))))

(deftest list-sessions-includes-observability-test
  (testing "list-agent-sessions includes observability fields"
    (let [started (session/start-agent-session!
                    {::session/namespace 'test.obs
                     ::session/pool nil})
          session-id (::session/id started)]

      ;; Do some evals
      (session/record-eval-start! {::session/id session-id ::session/code "(+ 1 2)"})
      (session/record-eval-complete! {::session/id session-id})

      (let [sessions (session/list-agent-sessions {})
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
                    {::session/namespace 'test.nrepl.sid
                     ::session/pool nil})
          session-id (::session/id started)
          nrepl-sid "test-nrepl-session-123"]

      ;; Set the nREPL session ID
      (let [result (session/set-nrepl-session-id!
                     {::session/id session-id
                      ::session/nrepl-session-id nrepl-sid})]
        (is (true? (::session/set result))))

      ;; Verify it's stored
      (let [info (session/get-session-port
                   {::session/id session-id})]
        (is (= nrepl-sid (::session/nrepl-session-id info))))))

  (testing "returns false for non-existent session"
    (let [result (session/set-nrepl-session-id!
                   {::session/id "dead00"
                    ::session/nrepl-session-id "test-123"})]
      (is (false? (::session/set result))))))

(deftest get-session-port-includes-nrepl-session-id-test
  (testing "get-session-port returns nrepl-session-id when set"
    (let [started (session/start-agent-session!
                    {::session/namespace 'test.port.sid
                     ::session/pool nil})
          session-id (::session/id started)
          nrepl-sid "my-nrepl-session-456"]

      ;; Initially nil
      (let [info (session/get-session-port
                   {::session/id session-id})]
        (is (nil? (::session/nrepl-session-id info))))

      ;; Set it
      (session/set-nrepl-session-id!
        {::session/id session-id
         ::session/nrepl-session-id nrepl-sid})

      ;; Now returned
      (let [info (session/get-session-port
                   {::session/id session-id})]
        (is (= nrepl-sid (::session/nrepl-session-id info)))))))

;;; ---------------------------------------------------------------------------
;;; Runtime Registry Integration Tests (Phase 2)
;;; ---------------------------------------------------------------------------

(deftest start-session-registers-in-runtime-test
  (testing "start-agent-session! registers instance in runtime registry"
    (let [result (session/start-agent-session!
                   {::session/namespace 'test.runtime.start
                    ::session/pool nil})
          session-id (::session/id result)
          instances (runtime/instances {})]
      (is (= 1 (count instances)))
      (let [inst (first instances)]
        (is (= "test.runtime.start" (::runtime/namespace inst)))
        (is (= :running (::runtime/status inst)))
        (is (= :external (::runtime/location inst)))
        (is (= session-id (::runtime/session-id inst)))
        (is (inst? (::runtime/started-at inst)))
        ;; No pool in tests, so no nrepl-port
        (is (nil? (::runtime/nrepl-port inst)))))))

(deftest stop-session-unregisters-from-runtime-test
  (testing "stop-agent-session! sets runtime status to :stopped"
    (let [started (session/start-agent-session!
                    {::session/namespace 'test.runtime.stop
                     ::session/pool nil})
          session-id (::session/id started)]
      ;; Verify running first
      (let [inst (runtime/instance {::runtime/namespace "test.runtime.stop"})]
        (is (= :running (::runtime/status inst))))

      ;; Stop it
      (session/stop-agent-session! {::session/id session-id})

      ;; Verify stopped in runtime
      (let [inst (runtime/instance {::runtime/namespace "test.runtime.stop"})]
        (is (= :stopped (::runtime/status inst)))
        (is (inst? (::runtime/stopped-at inst)))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.session-test)

  ;; Run specific test
  (clojure.test/test-var #'start-agent-session-test)
  (clojure.test/test-var #'activity-tracking-test))
