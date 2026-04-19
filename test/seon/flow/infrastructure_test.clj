(ns seon.flow.infrastructure-test
  "Tests for the infrastructure flow: build-infrastructure!, infra-writer-step,
   and the full write→reply-router pipeline."
  (:require [clojure.core.async.flow :as flow]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.db.datalevin.conn :as dl-conn]
            [seon.db.datalevin.writer :as writer]
            [seon.flow.msg :as msg]
            [seon.flow.status :as status]
            [seon.flow.topology :as topology]
            [seon.runtime :as runtime]
            [seon.test-utils :as tu])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- make-fake-manager
  "Create a fake connection manager with a pre-loaded connection.
   The connection is stored under the given db keyword so get-conn!
   returns it without needing a Datalevin server."
  [db-keyword conn]
  {::dl-conn/port 0
   ::dl-conn/host "127.0.0.1"
   ::dl-conn/username "test"
   ::dl-conn/password "test"
   ::dl-conn/connections (atom {db-keyword {::dl-conn/connection conn
                                            ::dl-conn/last-accessed (Instant/now)}})})

(def ^:private test-schema
  {:name {:db/valueType :db.type/string}
   :age  {:db/valueType :db.type/long}})

(defn- clean-promises!
  "Reset pending promises between tests."
  []
  (reset! topology/pending-promises {}))

(use-fixtures :each (fn [f] (clean-promises!) (f) (clean-promises!)))

(defn- stop-infra!
  "Stop infrastructure flow safely: pause -> ping -> stop, then cleanup registrations."
  [{::topology/keys [flow flow-id]}]
  (when flow
    (try
      (flow/pause flow)
      (flow/ping flow :timeout-ms 3000)
      (catch Throwable _))
    (flow/stop flow))
  (when flow-id
    (try (status/stop-error-drain! {::status/id flow-id}) (catch Throwable _))
    (try (runtime/unregister-flow! {::runtime/flow-id flow-id}) (catch Throwable _))))

;;; ---------------------------------------------------------------------------
;;; infra-writer-step — Unit Tests (describe, init, transform)
;;; ---------------------------------------------------------------------------

(deftest infra-writer-describe-test
  (testing "describe returns correct ins, outs, and workload"
    (let [desc (writer/infra-writer-step)]
      (is (contains? (:ins desc) :seon.flow.in/request))
      (is (contains? (:outs desc) :seon.flow.out/reply))
      (is (contains? (:outs desc) :seon.flow.out/error))
      (is (= :io (:workload desc))))))

(deftest infra-writer-init-test
  (testing "init returns zeroed counters with connection-manager"
    (let [cm {:fake "manager"}
          state (writer/infra-writer-step {::writer/connection-manager cm})]
      (is (= cm (::writer/connection-manager state)))
      (is (= 0 (::writer/total-writes state)))
      (is (= 0 (::writer/total-errors state)))
      (is (nil? (::writer/last-write-at state))))))

(deftest infra-writer-transform-success-test
  (testing "successful write emits reply envelope with :ok status"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (let [cm (make-fake-manager :test-db conn)
              state (writer/infra-writer-step {::writer/connection-manager cm})
              request-id (random-uuid)
              request {::msg/id request-id
                       ::msg/payload {::writer/tx-data [{:name "Alice" :age 30}]
                                      ::writer/db-name "test-db"}}
              [state' outputs] (writer/infra-writer-step state :seon.flow.in/request request)]
          ;; State updated
          (is (= 1 (::writer/total-writes state')))
          (is (instance? Instant (::writer/last-write-at state')))
          ;; Reply envelope emitted
          (let [reply (first (:seon.flow.out/reply outputs))]
            (is (= request-id (::msg/id reply)))
            (is (= :ok (::msg/status reply)))
            (is (= :reply (::msg/type reply)))
            (is (= "seon.db.writer" (::msg/from-ns reply))))
          ;; Data actually in DB
          (let [results (d/q '[:find ?n ?a :where [?e :name ?n] [?e :age ?a]] @conn)]
            (is (= #{["Alice" 30]} (set results)))))))))

(deftest infra-writer-transform-error-test
  (testing "bad tx-data emits error reply and error output"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (let [cm (make-fake-manager :test-db conn)
              state (writer/infra-writer-step {::writer/connection-manager cm})
              request-id (random-uuid)
              request {::msg/id request-id
                       ::msg/payload {::writer/tx-data [:not-valid]
                                      ::writer/db-name "test-db"}}
              [state' outputs] (writer/infra-writer-step state :seon.flow.in/request request)]
          ;; Error count incremented
          (is (= 1 (::writer/total-errors state')))
          (is (= 0 (::writer/total-writes state')))
          ;; Error reply emitted on both reply and error outputs
          (let [reply (first (:seon.flow.out/reply outputs))]
            (is (= request-id (::msg/id reply)))
            (is (= :error (::msg/status reply)))
            (is (= :execution (::msg/error-type reply)))
            (is (string? (::msg/error-message reply))))
          (is (some? (first (:seon.flow.out/error outputs)))))))))

(deftest infra-writer-transition-test
  (testing "transitions return state without error"
    (let [state (writer/infra-writer-step {::writer/connection-manager {:fake true}})
          s-pause (writer/infra-writer-step state :clojure.core.async.flow/pause)
          s-resume (writer/infra-writer-step state :clojure.core.async.flow/resume)
          s-stop (writer/infra-writer-step state :clojure.core.async.flow/stop)]
      (is (= state s-pause))
      (is (= state s-resume))
      (is (= state s-stop)))))

;;; ---------------------------------------------------------------------------
;;; build-infrastructure! — Integration Tests
;;; ---------------------------------------------------------------------------

(deftest build-infrastructure-starts-test
  (testing "build-infrastructure! creates a flow with all 5 processes running"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (let [cm (make-fake-manager :test-db conn)
              infra (topology/build-infrastructure!
                     {::topology/connection-manager cm})]
          (try
            (is (some? (::topology/flow infra)))
            (is (= :seon.flow/infrastructure (::topology/flow-id infra)))
            ;; Ping proves all processes are running
            (let [ping-result (flow/ping (::topology/flow infra) :timeout-ms 5000)]
              (is (some? ping-result))
              ;; All 5 processes should show state
              (is (contains? ping-result :seon.flow/writer))
              (is (contains? ping-result :seon.flow/repl))
              (is (contains? ping-result :seon.flow/reply-router))
              (is (contains? ping-result :seon.flow/event-sink))
              (is (contains? ping-result :seon.flow/error-sink)))
            (finally
              (stop-infra! infra))))))))

(deftest build-infrastructure-write-and-reply-test
  (testing "inject write request, get reply through reply-router with msg/id correlation"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (let [cm (make-fake-manager :test-db conn)
              infra (topology/build-infrastructure!
                     {::topology/connection-manager cm})]
          (try
            (let [request-id (random-uuid)
                  p (promise)
                  _ (swap! topology/pending-promises assoc request-id p)
                  request {::msg/id request-id
                           ::msg/version 1
                           ::msg/type :request
                           ::msg/payload {::writer/tx-data [{:name "Bob" :age 25}]
                                          ::writer/db-name "test-db"}}]
              ;; Inject into writer process
              (flow/inject (::topology/flow infra)
                           [:seon.flow/writer :seon.flow.in/request]
                           [request])
              ;; Wait for reply via promise
              (let [reply (deref p 5000 ::timeout)]
                (is (not= ::timeout reply) "Reply should be delivered via reply-router")
                (is (= request-id (::msg/id reply)))
                (is (= :ok (::msg/status reply))))
              ;; Verify data in DB
              (let [results (d/q '[:find ?n ?a :where [?e :name ?n] [?e :age ?a]] @conn)]
                (is (= #{["Bob" 25]} (set results)))))
            (finally
              (stop-infra! infra))))))))

(deftest build-infrastructure-error-reply-test
  (testing "bad tx-data produces error reply through reply-router"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (let [cm (make-fake-manager :test-db conn)
              infra (topology/build-infrastructure!
                     {::topology/connection-manager cm})]
          (try
            (let [request-id (random-uuid)
                  p (promise)
                  _ (swap! topology/pending-promises assoc request-id p)
                  request {::msg/id request-id
                           ::msg/version 1
                           ::msg/type :request
                           ::msg/payload {::writer/tx-data [:invalid-tx-data]
                                          ::writer/db-name "test-db"}}]
              (flow/inject (::topology/flow infra)
                           [:seon.flow/writer :seon.flow.in/request]
                           [request])
              (let [reply (deref p 5000 ::timeout)]
                (is (not= ::timeout reply) "Error reply should be delivered")
                (is (= request-id (::msg/id reply)))
                (is (= :error (::msg/status reply)))
                (is (= :execution (::msg/error-type reply)))
                (is (string? (::msg/error-message reply)))))
            (finally
              (stop-infra! infra))))))))

(deftest build-infrastructure-lifecycle-test
  (testing "start, pause, ping, resume, stop lifecycle without exceptions"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (let [cm (make-fake-manager :test-db conn)
              infra (topology/build-infrastructure!
                     {::topology/connection-manager cm})
              fl (::topology/flow infra)]
          (try
            ;; Already started and resumed by build-infrastructure!
            ;; Pause
            (flow/pause fl)
            (let [ping-after-pause (flow/ping fl :timeout-ms 3000)]
              (is (some? ping-after-pause) "Ping should succeed after pause"))
            ;; Resume
            (flow/resume fl)
            (let [ping-after-resume (flow/ping fl :timeout-ms 3000)]
              (is (some? ping-after-resume) "Ping should succeed after resume"))
            ;; Stop
            (flow/pause fl)
            (flow/ping fl :timeout-ms 3000)
            (flow/stop fl)
            (finally
              (try (status/stop-error-drain! {::status/id :seon.flow/infrastructure}) (catch Throwable _))
              (try (runtime/unregister-flow! {::runtime/flow-id :seon.flow/infrastructure}) (catch Throwable _)))))))))

(deftest build-infrastructure-multiple-writes-test
  (testing "multiple writes to same DB accumulate correctly"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (let [cm (make-fake-manager :test-db conn)
              infra (topology/build-infrastructure!
                     {::topology/connection-manager cm})]
          (try
            ;; Send 3 writes
            (let [promises (mapv (fn [i]
                                   (let [rid (random-uuid)
                                         p (promise)]
                                     (swap! topology/pending-promises assoc rid p)
                                     (flow/inject (::topology/flow infra)
                                                  [:seon.flow/writer :seon.flow.in/request]
                                                  [{::msg/id rid
                                                    ::msg/version 1
                                                    ::msg/type :request
                                                    ::msg/payload {::writer/tx-data [{:name (str "Person-" i)
                                                                                      :age (+ 20 i)}]
                                                                    ::writer/db-name "test-db"}}])
                                     p))
                                 (range 3))
                  replies (mapv #(deref % 5000 ::timeout) promises)]
              ;; All replies received
              (is (every? #(not= ::timeout %) replies))
              (is (every? #(= :ok (::msg/status %)) replies))
              ;; All 3 records in DB
              (let [results (d/q '[:find ?n :where [?e :name ?n]] @conn)]
                (is (= 3 (count results)))))
            (finally
              (stop-infra! infra))))))))
