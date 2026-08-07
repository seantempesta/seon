(ns seon.effect-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [datahike.core :as datahike]
            [seon.cluster.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.flow :as flow]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (test-support/environment "seon.effect-test")))


(def ^:private handler-calls (atom []))

(defn- test-handler
  {:malli/schema [:=> [:cat
                       [:map [:seon.effect-test/value :int]]
                       :seon.config/effective]
                  :map]}
  [request effective]
  (swap! handler-calls conj request)
  {:seon.effect-test/value (:seon.effect-test/value request)
   :seon.effect-test/cluster (:seon.config/cluster effective)
   :seon.effect-test/virtual-thread? (.isVirtual (Thread/currentThread))})

(defn capability-owner
  {:malli/schema [:=> [:cat [:map [:seon.effect-test/value :int]]]
                  [:or :map :seon.error/value]]}
  [request]
  request)

(defn- install-capability!
  [connection]
  (let [handler-meta (meta #'test-handler)]
    (db/transact!
     connection
     [{:seon.schema/key :seon.effect-test/request
       :seon.schema/form
       (pr-str [:map [:seon.effect-test/value :int]])}
      {:seon.fn/sym "seon.effect-test/capability-owner"
       :seon.fn/spec
       (pr-str [:=> [:cat :seon.effect-test/request]
                [:or :map :seon.error/value]])
       :seon.fn/workload :io
       :seon.effect/capability
       (symbol (str (ns-name (:ns handler-meta)))
               (str (:name handler-meta)))}])))

(defn- request-context
  ([connection]
   (request-context connection nil))
  ([connection launcher]
   {:seon.db/connection connection
    :seon.cluster.agent/id "effect-agent"
    :seon.cluster.run/id "effect-run"
    :seon.cluster.run.form/ordinal 3
    :seon.boot/cluster-name "default"
    :seon.flow/work-launcher launcher
    :seon.sci.admit/caps (config/result-caps (config/defaults))
    :seon.config/on-core-error :record
    :seon.effect/counter (atom -1)}))

(deftest background-request-returns-its-notifying-receipt-and-settles-once
  (test-support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.config/cluster "default"}
        {:seon.cluster.agent/id "effect-agent"}
        {:seon.cluster.run/id "effect-run"
         :seon.cluster.run/agent
         [:seon.cluster.agent/id "effect-agent"]}])
      (install-capability! connection)
      (let [events (async/chan 4)
            listener-key (random-uuid)
            _ (datahike/listen! connection listener-key #(async/put! events %))
            launcher
            (flow/start-work-launcher!
             {:seon.env/environment @test-environment
              ::flow/configuration
              {:seon.config.flow.compute/queue-depth 1
               :seon.config.flow.compute/concurrency 1
               :seon.config.flow.io/queue-depth 1
               :seon.config.flow.io/concurrency 1}})
            effect-id (pr-str ["effect-run" 3 0])
            result-ref [:seon.effect/id effect-id]]
        (try
          (is (= result-ref
                 (binding [effect/*request-context*
                           (request-context connection launcher)]
                   (effect/request!
                    #'capability-owner
                    {:seon.effect-test/value 7}
                    {:seon.effect/background? true}))))
          (test-support/await-event!
           events
           ::background-effect-settled
           #(:seon.effect/to
             (db/pull (:db-after %)
                      [{:seon.effect/to [:seon.cluster.agent/id]}]
                      [:seon.effect/id effect-id])))
          (let [receipt
                (db/pull @connection
                         '[* {:seon.effect/to
                              [:seon.cluster.agent/id]}]
                         [:seon.effect/id effect-id])]
            (is (= "effect-agent"
                   (get-in receipt
                           [:seon.effect/to :seon.cluster.agent/id])))
            (is (nil? (:seon.effect/notify receipt)))
            (is (int? (:seon.effect/duration-ms receipt)))
            (is (not (neg? (:seon.effect/duration-ms receipt)))))
          (finally
            (datahike/unlisten! connection listener-key)
            (async/close! events)
            (flow/stop-work-launcher! launcher)))))))

(deftest capability-reachability-is-a-database-query
  (test-support/with-database
    (fn [connection]
      (install-capability! connection)
      (db/transact!
       connection
       [{:seon.fn/sym "seon.effect-test/pure-caller"
         :seon.fn/calls
         [[:seon.fn/sym "seon.effect-test/capability-owner"]]}])
      (let [database @connection]
        (is (= #{"seon.effect-test/capability-owner"}
               (effect/capabilities database
                                    'seon.effect-test/capability-owner)))
        (is (= #{"seon.effect-test/capability-owner"}
               (effect/capabilities database
                                    'seon.effect-test/pure-caller)))
        (is (= #{}
               (effect/capabilities database
                                    'seon.effect-test/test-handler)))))))

(deftest request-commits-before-io-dispatch-and-settles-once
  (test-support/with-database
    (fn [connection]
      (reset! handler-calls [])
      (db/transact! connection [{:seon.config/cluster "default"}
                                {:seon.cluster.run/id "effect-run"}])
      (install-capability! connection)
      (let [first-result
            (binding [effect/*request-context* (request-context connection)]
              (effect/request! #'capability-owner
                               {:seon.effect-test/value 7}))
            receipt
            (db/pull @connection '[* {:seon.effect/owner [:seon.fn/sym]}]
                     [:seon.effect/id (pr-str ["effect-run" 3 0])])]
        (testing "the handler ran on the shared io executor with effective facts"
          (is (= 7 (:seon.effect-test/value first-result)))
          (is (true? (:seon.effect-test/virtual-thread? first-result)))
          (is (= [{:seon.effect-test/value 7}] @handler-calls)))
        (testing "one open-before-dispatch receipt settled with bounded data"
          (is (= "seon.effect-test/capability-owner"
                 (get-in receipt [:seon.effect/owner :seon.fn/sym])))
          (is (= 0 (:seon.effect/ordinal receipt)))
          (is (= 3 (:seon.effect/form-ordinal receipt)))
          (is (string? (:seon.effect/request-edn receipt)))
          (is (string? (:seon.effect/result-edn receipt)))
          (is (inst? (:seon.effect/opened-at receipt)))
          (is (inst? (:seon.effect/settled-at receipt))))
        (testing "the same identity refuses redispatch"
          (let [second-result
                (binding [effect/*request-context* (request-context connection)]
                  (effect/request! #'capability-owner
                                   {:seon.effect-test/value 7}))]
            (is (= :seon.effect/already-recorded
                   (:seon.error/kind second-result)))
            (is (= 1 (count @handler-calls)))))))))

(deftest invalid-requests-never-open-a-receipt
  (test-support/with-database
    (fn [connection]
      (install-capability! connection)
      (let [result
            (binding [effect/*request-context* (request-context connection)]
              (effect/request! #'capability-owner
                               {:seon.effect-test/value "wrong"}))]
        (is (= :seon.effect/invalid-request (:seon.error/kind result)))
        (is (nil? (db/pull @connection [:seon.effect/id]
                           [:seon.effect/id
                            (pr-str ["effect-run" 3 0])])))))))

(deftest interrupted-handlers-mark-the-open-receipt-without-a-result
  (test-support/with-database
    (fn [connection]
      (db/transact! connection [{:seon.config/cluster "default"}
                                {:seon.cluster.run/id "effect-run"}])
      (install-capability! connection)
      (with-redefs-fn
        {(ns-resolve 'seon.effect 'dispatch)
         (fn [_handler _request _effective]
           (throw (InterruptedException. "test interruption")))}
        (fn []
          (let [result
                (binding [effect/*request-context* (request-context connection)]
                  (effect/request! #'capability-owner
                                   {:seon.effect-test/value 7}))
                receipt
                (db/pull @connection '[*]
                         [:seon.effect/id
                          (pr-str ["effect-run" 3 0])])]
            (is (= :seon.effect/interrupted (:seon.error/kind result)))
            (is (inst? (:seon.effect/interrupted-at receipt)))
            (is (nil? (:seon.effect/result-edn receipt)))))))))

(deftest guarded-sci-evaluation-supplies-the-effect-identity-context
  (test-support/with-database
    (fn [connection]
      (db/transact! connection [{:seon.config/cluster "default"}
                                {:seon.cluster.run/id "effect-run"}])
      (install-capability! connection)
      (let [ctx (sci.eval/cluster-ctx @connection connection)
            effective (config/defaults)
            evaluation
            (sci.eval/evaluate
             {:seon.cluster.run.form/source
              (str "(seon.effect/request! "
                   "#'seon.effect-test/capability-owner "
                   "{:seon.effect-test/value 9})")
              :seon.cluster.run.form/ns [:seon.ns/name 'user]
              :seon.sci.admit/caps (config/result-caps effective)
              :seon.sci.eval/time-limit-ms
              (:seon.config.eval/time-limit-ms effective)
              :seon.config/on-core-error :record
              :seon.sci.eval/ctx ctx
              :seon.cluster.agent/id "root"
              :seon.cluster.run/id "effect-run"
              :seon.cluster.run.form/ordinal 3
              :seon.boot/cluster-name "default"})
            receipt
            (db/pull @connection '[*]
                     [:seon.effect/id (pr-str ["effect-run" 3 0])])]
        (is (= 9 (get-in evaluation
                         [:seon.sci.admit/value
                          :seon.effect-test/value])))
        (is (= 0 (:seon.effect/ordinal receipt)))
        (is (inst? (:seon.effect/settled-at receipt)))))))

(deftest recovery-marks-open-receipts-interrupted-without-refiring
  (test-support/with-database
    (fn [connection]
      (let [opened-at (Date. 1699999999000)
            now (Date. 1700000000000)]
        (db/transact! connection [{:seon.cluster.agent/id "effect-agent"}])
        (db/transact!
         connection
         (run/open-tx
          {::run/id "effect-run"
           ::run/agent [:seon.cluster.agent/id "effect-agent"]
           ::run/opened-at opened-at}))
        (db/transact!
         connection
         (run/claim-tx
          {::run/id "effect-run"
           ::run/process "dead-process"
           ::run/live-processes #{"dead-process"}
           ::run/now opened-at}))
        (install-capability! connection)
        (db/transact!
         connection
         [{:seon.effect/id (pr-str ["effect-run" 3 0])
           :seon.effect/run [:seon.cluster.run/id "effect-run"]
           :seon.effect/owner [:seon.fn/sym "seon.effect-test/capability-owner"]
           :seon.effect/form-ordinal 3
           :seon.effect/ordinal 0
           :seon.effect/request-edn "{}"
           :seon.effect/opened-at now}])
        (db/transact!
         connection
         (run/recover-tx
          {::run/id "effect-run"
           ::run/live-processes #{"live-process"}
           ::run/now now}))
        (let [receipt (db/pull @connection '[*]
                               [:seon.effect/id
                                (pr-str ["effect-run" 3 0])])]
          (is (= now (:seon.effect/interrupted-at receipt)))
          (is (nil? (:seon.effect/result-edn receipt)))
          (is (some? (::run/closed-at
                      (db/pull @connection '[*]
                               [:seon.cluster.run/id "effect-run"])))))))))
