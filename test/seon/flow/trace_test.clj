(ns seon.flow.trace-test
  "Tests for flow event tracing and persistence.

   Uses a temporary local Datalevin database with db/*direct-mode*
   to bypass the infrastructure flow, matching the standard test pattern."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.db.datalevin.conn :as conn]
            [seon.flow.trace :as trace]
            [seon.test-utils :as tu])
  (:import [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; Test Fixture
;;; ---------------------------------------------------------------------------

(defn with-temp-conn [f]
  (tu/with-temp-conn trace/datalevin-schema
    (fn [temp-conn]
      (let [mock-manager {::conn/connections (atom {:seon.flow {::conn/connection temp-conn}})}]
        (binding [db/*direct-mode* true
                  db/*conn-manager* mock-manager]
          (f))))))

(use-fixtures :each with-temp-conn)

;;; ---------------------------------------------------------------------------
;;; persist-event! tests
;;; ---------------------------------------------------------------------------

(deftest persist-event-basic-test
  (testing "persist-event! stores a minimal event and returns true"
    (let [trace-id (UUID/randomUUID)
          result (trace/persist-event! {::trace/trace-id trace-id
                                        ::trace/event :start
                                        ::trace/fn "seon.example/foo"
                                        ::trace/ns "seon.example"})]
      (is (true? result))))

  (testing "persist-event! stores event with all optional fields"
    (let [trace-id (UUID/randomUUID)
          result (trace/persist-event! {::trace/trace-id trace-id
                                        ::trace/session-id "ab12"
                                        ::trace/event :end
                                        ::trace/fn "seon.example/bar"
                                        ::trace/ns "seon.example"
                                        ::trace/elapsed-ms 42
                                        ::trace/status :ok})]
      (is (true? result)))))

(deftest persist-event-error-event-test
  (testing "persist-event! stores error events with error-message"
    (let [trace-id (UUID/randomUUID)
          result (trace/persist-event! {::trace/trace-id trace-id
                                        ::trace/session-id "cd34"
                                        ::trace/event :error
                                        ::trace/fn "seon.example/baz"
                                        ::trace/ns "seon.example"
                                        ::trace/status :error
                                        ::trace/error-message "Connection refused"})]
      (is (true? result)))))

;;; ---------------------------------------------------------------------------
;;; events-for-session tests
;;; ---------------------------------------------------------------------------

(deftest events-for-session-round-trip-test
  (testing "events-for-session returns persisted events for a session"
    (let [session-id (subs (str (UUID/randomUUID)) 0 4)
          trace-id-1 (UUID/randomUUID)
          trace-id-2 (UUID/randomUUID)]
      ;; Persist two events for the same session
      (trace/persist-event! {::trace/trace-id trace-id-1
                              ::trace/session-id session-id
                              ::trace/event :start
                              ::trace/fn "seon.test/alpha"
                              ::trace/ns "seon.test"})
      (Thread/sleep 10) ;; ensure distinct timestamps
      (trace/persist-event! {::trace/trace-id trace-id-2
                              ::trace/session-id session-id
                              ::trace/event :end
                              ::trace/fn "seon.test/alpha"
                              ::trace/ns "seon.test"
                              ::trace/elapsed-ms 100
                              ::trace/status :ok})

      (let [events (trace/events-for-session {::trace/session-id session-id})]
        (is (>= (count events) 2))
        ;; Newest first
        (is (= :end (::trace/event (first events))))
        (is (= :start (::trace/event (second events))))
        ;; Fields present
        (is (some? (::trace/timestamp (first events))))
        (is (= "seon.test/alpha" (::trace/fn (first events))))))))

(deftest events-for-session-empty-test
  (testing "returns empty for unknown session"
    (let [events (trace/events-for-session {::trace/session-id "zzzz"})]
      (is (empty? events)))))

(deftest events-for-session-limit-test
  (testing "respects limit parameter"
    (let [session-id (subs (str (UUID/randomUUID)) 0 4)]
      ;; Persist 5 events
      (dotimes [i 5]
        (trace/persist-event! {::trace/trace-id (UUID/randomUUID)
                                ::trace/session-id session-id
                                ::trace/event :start
                                ::trace/fn (str "seon.test/fn-" i)
                                ::trace/ns "seon.test"})
        (Thread/sleep 5))

      (let [events (trace/events-for-session {::trace/session-id session-id
                                               ::trace/limit 2})]
        (is (= 2 (count events)))))))
