(ns seon.flow.trace-test
  "Tests for flow event tracing + persistence. Ported in M-2b from the
   legacy datalevin shape to the canonical datahike `:memory` fixture.

   `seon.flow.trace/persist-event!` writes to `:seon.flow` (which is in
   `:seon.db/flow` on the running system); the fixture stands up an
   isolated `:seon.flow` flow with the trace entity schema installed,
   so persist-event! routes through the fixture's conn-process."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.flow.trace :as trace]
            [seon.test-utils :as tu])
  (:import [java.util UUID]))

(use-fixtures :each
  (tu/with-test-db-fixture
    {::tu/namespaces [:seon.flow]
     ::tu/schemas    {:seon.flow trace/entity-schema}}))

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
      (trace/persist-event! {::trace/trace-id trace-id-1
                             ::trace/session-id session-id
                             ::trace/event :start
                             ::trace/fn "seon.test/alpha"
                             ::trace/ns "seon.test"})
      (Thread/sleep 10)
      (trace/persist-event! {::trace/trace-id trace-id-2
                             ::trace/session-id session-id
                             ::trace/event :end
                             ::trace/fn "seon.test/alpha"
                             ::trace/ns "seon.test"
                             ::trace/elapsed-ms 100
                             ::trace/status :ok})

      (let [events (trace/events-for-session {::trace/session-id session-id})]
        (is (>= (count events) 2))
        (is (= :end (::trace/event (first events))))
        (is (= :start (::trace/event (second events))))
        (is (some? (::trace/timestamp (first events))))
        (is (= "seon.test/alpha" (::trace/fn (first events))))))))

(deftest events-for-session-empty-test
  (testing "returns empty for unknown session"
    (let [events (trace/events-for-session {::trace/session-id "zzzz"})]
      (is (empty? events)))))

(deftest events-for-session-limit-test
  (testing "respects limit parameter"
    (let [session-id (subs (str (UUID/randomUUID)) 0 4)]
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
