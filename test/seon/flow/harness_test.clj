(ns seon.flow.harness-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [seon.flow.harness :as harness]
            [seon.flow.msg :as msg])
  (:import [java.io File]
           [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- make-request
  "Build a minimal valid request envelope."
  [& {:keys [id from-ns to-ns fn-name args trace-id]
      :or {from-ns "seon.test.alpha"
           to-ns "seon.test.beta"
           fn-name "seon.test.beta/format-name"
           args [{:seon.test.beta/raw-name "sean"}]}}]
  (cond-> {::msg/id (or id (random-uuid))
           ::msg/version 1
           ::msg/type :request
           ::msg/from-ns from-ns
           ::msg/to-ns to-ns
           ::msg/fn fn-name
           ::msg/args args
           ::msg/created-at (Instant/now)}
    trace-id (assoc ::msg/trace-id trace-id)))

(defn- make-reply
  "Build a minimal valid reply envelope."
  [request & {:keys [status value error-type error-message]
              :or {status :ok value {:result 42}}}]
  (cond-> {::msg/id (::msg/id request)
           ::msg/version 1
           ::msg/type :reply
           ::msg/status status
           ::msg/from-ns "seon.test.beta"
           ::msg/duration-ms 5}
    (= status :ok) (assoc ::msg/value value)
    error-type (assoc ::msg/error-type error-type)
    error-message (assoc ::msg/error-message error-message)
    (::msg/trace-id request) (assoc ::msg/trace-id (::msg/trace-id request))))

(defn- init-state
  "Create an initialized harness state for testing."
  [& {:keys [queue-cap namespace]
      :or {queue-cap 32 namespace "seon.test.beta"}}]
  (harness/namespace-step {::harness/namespace namespace
                           ::harness/queue-cap queue-cap}))

;;; ---------------------------------------------------------------------------
;;; Describe
;;; ---------------------------------------------------------------------------

(deftest describe-test
  (testing "describe returns ins, outs, params, workload"
    (let [desc (harness/namespace-step)]
      (is (contains? (:ins desc) :seon.flow.in/request))
      (is (contains? (:outs desc) :seon.flow.out/reply))
      (is (contains? (:outs desc) :seon.flow.out/error))
      (is (contains? (:outs desc) :seon.flow.out/event))
      (is (contains? (:params desc) ::harness/namespace))
      (is (contains? (:params desc) ::harness/queue-cap))
      (is (= :io (:workload desc))))))

;;; ---------------------------------------------------------------------------
;;; Init
;;; ---------------------------------------------------------------------------

(deftest init-test
  (testing "init sets defaults"
    (let [state (init-state)]
      (is (= "seon.test.beta" (::harness/namespace state)))
      (is (= 32 (::harness/queue-cap state)))
      (is (= 0 (::harness/pending state)))
      (is (= 0 (::harness/error-count state)))))

  (testing "init accepts custom queue-cap"
    (let [state (init-state :queue-cap 5)]
      (is (= 5 (::harness/queue-cap state)))))

  (testing "init merges in-ports and out-ports when provided"
    (let [fake-in-ch :fake-in
          fake-out-ch :fake-out
          state (harness/namespace-step
                 {::harness/namespace "seon.test.beta"
                  ::harness/queue-cap 8
                  ::harness/in-ports {:seon.flow.in/jvm-reply fake-in-ch}
                  ::harness/out-ports {:seon.flow.out/jvm-request fake-out-ch}})]
      (is (= fake-in-ch
             (get-in state [:clojure.core.async.flow/in-ports :seon.flow.in/jvm-reply])))
      (is (= fake-out-ch
             (get-in state [:clojure.core.async.flow/out-ports :seon.flow.out/jvm-request]))))))

;;; ---------------------------------------------------------------------------
;;; Transform: Happy Path
;;; ---------------------------------------------------------------------------

(deftest happy-path-request-test
  (testing "request is forwarded to JVM and increments pending"
    (let [state (init-state)
          req (make-request)
          [state' outputs] (harness/namespace-step state :seon.flow.in/request req)]
      (is (= 1 (::harness/pending state')))
      (is (= [req] (:seon.flow.out/jvm-request outputs)))
      (is (nil? (:seon.flow.out/reply outputs))))))

(deftest happy-path-reply-test
  (testing "JVM reply is forwarded and decrements pending"
    (let [state (init-state)
          req (make-request)
          ;; First: forward request
          [state' _] (harness/namespace-step state :seon.flow.in/request req)
          ;; Then: receive reply
          reply (make-reply req)
          [state'' outputs] (harness/namespace-step state' :seon.flow.in/jvm-reply reply)]
      (is (= 0 (::harness/pending state'')))
      (is (= [reply] (:seon.flow.out/reply outputs)))
      ;; ok reply should not appear on error output
      (is (nil? (:seon.flow.out/error outputs)))
      ;; should emit :ok event
      (let [events (:seon.flow.out/event outputs)]
        (is (= 1 (count events)))
        (is (= :ok (::msg/event-kind (first events))))))))

;;; ---------------------------------------------------------------------------
;;; Transform: Overload
;;; ---------------------------------------------------------------------------

(deftest overload-test
  (testing "overload when pending >= queue-cap"
    (let [state (init-state :queue-cap 2)
          req1 (make-request)
          req2 (make-request)
          req3 (make-request)
          ;; Fill queue
          [s1 _] (harness/namespace-step state :seon.flow.in/request req1)
          [s2 _] (harness/namespace-step s1 :seon.flow.in/request req2)
          ;; Third request should overload
          [s3 outputs] (harness/namespace-step s2 :seon.flow.in/request req3)]
      ;; Pending should NOT increase
      (is (= 2 (::harness/pending s3)))
      ;; Reply should be overload error
      (let [reply (first (:seon.flow.out/reply outputs))]
        (is (= :overload (::msg/status reply)))
        (is (= :overload (::msg/error-type reply)))
        (is (= (::msg/id req3) (::msg/id reply)))
        (is (= 0 (::msg/duration-ms reply))))
      ;; Event should be overload
      (let [event (first (:seon.flow.out/event outputs))]
        (is (= :overload (::msg/event-kind event)))
        (is (= "seon.test.beta" (::msg/from-ns event)))))))

;;; ---------------------------------------------------------------------------
;;; Transform: Error Forwarding
;;; ---------------------------------------------------------------------------

(deftest error-forwarding-test
  (testing "error reply appears on both reply and error outputs"
    (let [state (init-state)
          req (make-request)
          [s1 _] (harness/namespace-step state :seon.flow.in/request req)
          error-reply (make-reply req
                                  :status :error
                                  :error-type :execution
                                  :error-message "boom")
          [s2 outputs] (harness/namespace-step s1 :seon.flow.in/jvm-reply error-reply)]
      (is (= 0 (::harness/pending s2)))
      (is (= 1 (::harness/error-count s2)))
      ;; On reply output
      (is (= [error-reply] (:seon.flow.out/reply outputs)))
      ;; On error output
      (is (= [error-reply] (:seon.flow.out/error outputs)))
      ;; Event should be :error
      (is (= :error (::msg/event-kind (first (:seon.flow.out/event outputs))))))))

;;; ---------------------------------------------------------------------------
;;; Transform: Pending Count Tracking
;;; ---------------------------------------------------------------------------

(deftest pending-count-test
  (testing "pending tracks correctly across multiple requests and replies"
    (let [state (init-state)
          req1 (make-request)
          req2 (make-request)
          ;; Send two requests
          [s1 _] (harness/namespace-step state :seon.flow.in/request req1)
          [s2 _] (harness/namespace-step s1 :seon.flow.in/request req2)
          _ (is (= 2 (::harness/pending s2)))
          ;; Reply to first
          [s3 _] (harness/namespace-step s2 :seon.flow.in/jvm-reply (make-reply req1))
          _ (is (= 1 (::harness/pending s3)))
          ;; Reply to second
          [s4 _] (harness/namespace-step s3 :seon.flow.in/jvm-reply (make-reply req2))]
      (is (= 0 (::harness/pending s4))))))

;;; ---------------------------------------------------------------------------
;;; Transform: Event Emission
;;; ---------------------------------------------------------------------------

(deftest event-emission-test
  (testing "ok reply emits :ok event"
    (let [state (-> (init-state)
                    (assoc ::harness/pending 1))
          req (make-request)
          reply (make-reply req)
          [_ outputs] (harness/namespace-step state :seon.flow.in/jvm-reply reply)
          event (first (:seon.flow.out/event outputs))]
      (is (= :ok (::msg/event-kind event)))
      (is (= :event (::msg/type event)))
      (is (= 1 (::msg/version event)))
      (is (inst? (::msg/created-at event)))
      (is (uuid? (::msg/id event)))))

  (testing "error reply emits :error event"
    (let [state (-> (init-state)
                    (assoc ::harness/pending 1))
          req (make-request)
          reply (make-reply req :status :error :error-type :execution :error-message "fail")
          [_ outputs] (harness/namespace-step state :seon.flow.in/jvm-reply reply)
          event (first (:seon.flow.out/event outputs))]
      (is (= :error (::msg/event-kind event)))))

  (testing "overload emits :overload event"
    (let [state (-> (init-state :queue-cap 1)
                    (assoc ::harness/pending 1))
          req (make-request)
          [_ outputs] (harness/namespace-step state :seon.flow.in/request req)
          event (first (:seon.flow.out/event outputs))]
      (is (= :overload (::msg/event-kind event))))))

;;; ---------------------------------------------------------------------------
;;; Transform: Trace ID Propagation
;;; ---------------------------------------------------------------------------

(deftest trace-id-propagation-test
  (testing "overload reply preserves trace-id from request"
    (let [trace (random-uuid)
          state (-> (init-state :queue-cap 0)
                    (assoc ::harness/pending 0 ::harness/queue-cap 0))
          req (make-request :trace-id trace)
          [_ outputs] (harness/namespace-step state :seon.flow.in/request req)
          reply (first (:seon.flow.out/reply outputs))]
      (is (= trace (::msg/trace-id reply))))))

;;; ---------------------------------------------------------------------------
;;; Transform: Unknown Input
;;; ---------------------------------------------------------------------------

(deftest unknown-input-test
  (testing "unknown input-id returns state unchanged with nil output"
    (let [state (init-state)
          [state' outputs] (harness/namespace-step state :seon.flow.in/unknown {:foo 1})]
      (is (= state state'))
      (is (nil? outputs)))))

;;; ---------------------------------------------------------------------------
;;; Transition
;;; ---------------------------------------------------------------------------

(deftest transition-test
  (testing "transitions return state unchanged"
    (let [state (init-state)]
      (is (= state (harness/namespace-step state :clojure.core.async.flow/stop)))
      (is (= state (harness/namespace-step state :clojure.core.async.flow/pause)))
      (is (= state (harness/namespace-step state :clojure.core.async.flow/resume))))))

;;; ---------------------------------------------------------------------------
;;; *ctx* Persistence
;;; ---------------------------------------------------------------------------

(defn- temp-dir []
  (let [dir (File/createTempFile "seon-harness-ctx-test" "")]
    (.delete dir)
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(defn- delete-dir [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (doseq [child (.listFiles f)]
        (if (.isDirectory child)
          (delete-dir (.getAbsolutePath child))
          (.delete child)))
      (.delete f))))

(defn- with-temp-conn [f]
  (let [dir (temp-dir)
        conn (d/get-conn dir)]
    (try
      (f conn)
      (finally
        (d/close conn)
        (delete-dir dir)))))

(deftest persist-ctx-round-trip-test
  (testing "round-trip: persist then load returns same data"
    (with-temp-conn
      (fn [conn]
        (let [data {:seon.test/counter 42
                    :seon.test/name "alpha"}
              ctx (atom data)]
          (harness/persist-ctx! {::harness/ctx ctx
                                 ::harness/namespace "seon.test.alpha"
                                 ::harness/conn conn})
          (let [loaded (harness/load-ctx! {::harness/namespace "seon.test.alpha"
                                           ::harness/conn conn})]
            (is (= data loaded))))))))

(deftest persist-ctx-non-serializable-test
  (testing "non-serializable values are skipped with warning"
    (with-temp-conn
      (fn [conn]
        (let [data {:seon.test/counter 42
                    :seon.test/bad-fn (fn [] "nope")}
              ctx (atom data)
              result (harness/persist-ctx! {::harness/ctx ctx
                                            ::harness/namespace "seon.test.alpha"
                                            ::harness/conn conn})]
          (is (= {:seon.test/counter 42} result))
          (let [loaded (harness/load-ctx! {::harness/namespace "seon.test.alpha"
                                           ::harness/conn conn})]
            (is (= {:seon.test/counter 42} loaded))))))))

(deftest persist-ctx-overwrite-test
  (testing "second persist overwrites first, load returns latest"
    (with-temp-conn
      (fn [conn]
        (harness/persist-ctx! {::harness/ctx {:seon.test/v 1}
                               ::harness/namespace "seon.test.alpha"
                               ::harness/conn conn})
        (harness/persist-ctx! {::harness/ctx {:seon.test/v 2}
                               ::harness/namespace "seon.test.alpha"
                               ::harness/conn conn})
        (let [loaded (harness/load-ctx! {::harness/namespace "seon.test.alpha"
                                         ::harness/conn conn})]
          (is (= {:seon.test/v 2} loaded)))))))

(deftest persist-ctx-empty-test
  (testing "empty map round-trips correctly"
    (with-temp-conn
      (fn [conn]
        (harness/persist-ctx! {::harness/ctx {}
                               ::harness/namespace "seon.test.alpha"
                               ::harness/conn conn})
        (let [loaded (harness/load-ctx! {::harness/namespace "seon.test.alpha"
                                         ::harness/conn conn})]
          (is (= {} loaded)))))))

(deftest persist-ctx-namespace-isolation-test
  (testing "different namespaces have isolated ctx data"
    (with-temp-conn
      (fn [conn]
        (harness/persist-ctx! {::harness/ctx {:seon.test/v "alpha"}
                               ::harness/namespace "seon.test.alpha"
                               ::harness/conn conn})
        (harness/persist-ctx! {::harness/ctx {:seon.test/v "beta"}
                               ::harness/namespace "seon.test.beta"
                               ::harness/conn conn})
        (is (= {:seon.test/v "alpha"}
               (harness/load-ctx! {::harness/namespace "seon.test.alpha"
                                   ::harness/conn conn})))
        (is (= {:seon.test/v "beta"}
               (harness/load-ctx! {::harness/namespace "seon.test.beta"
                                   ::harness/conn conn})))))))

(deftest load-ctx-missing-test
  (testing "load returns nil for unknown namespace"
    (with-temp-conn
      (fn [conn]
        (is (nil? (harness/load-ctx! {::harness/namespace "seon.test.unknown"
                                      ::harness/conn conn})))))))
