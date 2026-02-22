(ns seon.flow.trace-test
  "Tests for flow event tracing and persistence.

   These tests require a running system with Datalevin (./bin/run).
   They verify persist-event! and events-for-session round-trip correctly.

   When the system is not running, tests are skipped gracefully."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db.datalevin.conn :as conn]
            [seon.flow.trace :as trace]
            [integrant.repl.state :as state])
  (:import [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- system-available?
  "Check if the Datalevin connection manager is available."
  []
  (boolean
   (try
     (when-let [mgr (:seon.db.datalevin/connections state/system)]
       (conn/get-master-conn! {::conn/manager mgr}))
     (catch Exception _ nil))))

(defmacro when-system
  "Run body only if system is available, otherwise skip with message.
   Always produces at least one assertion so kaocha doesn't flag as failure."
  [& body]
  `(if (system-available?)
     (do ~@body)
     (is (not (system-available?)) "SKIP: requires running system")))

;;; ---------------------------------------------------------------------------
;;; persist-event! tests
;;; ---------------------------------------------------------------------------

(deftest persist-event-basic-test
  (when-system
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
       (is (true? result))))))

(deftest persist-event-error-event-test
  (when-system
   (testing "persist-event! stores error events with error-message"
     (let [trace-id (UUID/randomUUID)
           result (trace/persist-event! {::trace/trace-id trace-id
                                         ::trace/session-id "cd34"
                                         ::trace/event :error
                                         ::trace/fn "seon.example/baz"
                                         ::trace/ns "seon.example"
                                         ::trace/status :error
                                         ::trace/error-message "Connection refused"})]
       (is (true? result))))))

;;; ---------------------------------------------------------------------------
;;; events-for-session tests
;;; ---------------------------------------------------------------------------

(deftest events-for-session-round-trip-test
  (when-system
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
         (is (= "seon.test/alpha" (::trace/fn (first events)))))))))

(deftest events-for-session-empty-test
  (when-system
   (testing "returns empty for unknown session"
     (let [events (trace/events-for-session {::trace/session-id "zzzz"})]
       (is (empty? events))))))

(deftest events-for-session-limit-test
  (when-system
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
         (is (= 2 (count events))))))))
