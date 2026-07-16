(ns seon.db.request-receipt-test
  "Durable transaction request receipt and recovery tests."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [datahike.api :as d]
            [seon.db.executor :as executor]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- isolate-registry
  [test-fn]
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})]
    (try
      (test-fn)
      (finally
        (registry/restore-registry! {::registry/snapshot snapshot})))))

(use-fixtures :each isolate-registry)

(defn- runtime
  []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions
   (fn [_db-value _assertions] [])
   ::writer/knn-search (fn [_db-value _request] {:seon.embed/hits []})
   ::writer/publisher
   {::uds/channel (Object.)
    ::uds/subscribers (atom #{})
    ::uds/closed? (atom false)}})

(defn- ensure-database!
  [runtime database-name]
  (writer/handle-request
   runtime
   (protocol/ensure-database-request
    {::protocol/database-name database-name
     ::protocol/backend :memory})))

(defn- transact!
  [runtime database-name request-id transaction-data]
  (writer/handle-request
   runtime
   (protocol/transaction-request
    {::protocol/database-name database-name
     ::protocol/request-id request-id
     ::protocol/transaction-data transaction-data})))

(defn- connection
  [database-name]
  (::registry/conn
   (registry/lookup-connection
    {::registry/database-name (keyword database-name)})))

(defn- install-schema!
  [runtime database-name]
  (transact!
   runtime database-name (str "schema-" (random-uuid))
   [{:db/ident :receipt/value
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}]))

(deftest repeated-request-recovers-the-one-durable-commit
  (let [runtime (runtime)
        database-name (str "receipt-recovery-" (random-uuid))]
    (is (true? (::protocol/success?
                (ensure-database! runtime database-name))))
    (is (true? (::protocol/success?
                (install-schema! runtime database-name))))
    (let [connection (connection database-name)
          reports (atom [])
          _ (d/listen connection ::capture #(swap! reports conj %))
          request-id "receipt/recover"
          first-response
          (transact!
           runtime database-name request-id
           [{:db/id -1 :receipt/value "only-once"}])
          recovered-response
          (transact!
           runtime database-name request-id
           [(array-map :receipt/value "only-once" :db/id -1)])]
      (is (true? (::protocol/success? first-response)))
      (is (true? (::protocol/success? recovered-response)))
      (is (true? (::protocol/recovered? recovered-response)))
      (is (= (::protocol/coordinate first-response)
             (::protocol/coordinate recovered-response)))
      (is (= (::protocol/temporary-ids first-response)
             (::protocol/temporary-ids recovered-response)))
      (is (empty?
           (filter protocol/reserved-attributes
                   (map second (::protocol/transaction-data first-response))))
          "receipt implementation datoms are not public transaction datoms")
      (is (empty?
           (filter protocol/reserved-attributes
                   (keys (or (::protocol/transaction-meta first-response) {}))))
          "receipt implementation metadata is not public transaction metadata")
      (let [receipt
            (d/entity (d/db connection)
                      [::protocol/request-id request-id])]
        (is (uuid? (::protocol/request-hash receipt)))
        (is (= protocol/current-version (::protocol/version receipt))))
      (is (= 1 (count @reports))
          "a retry reconstructs history without firing listeners")
      (is (= 1
             (d/q '[:find (count ?entity) .
                    :where [?entity :receipt/value "only-once"]]
                  (d/db connection)))))))

(deftest concurrent-redelivery-serializes-to-one-commit
  (let [runtime (runtime)
        database-name (str "receipt-concurrent-" (random-uuid))]
    (ensure-database! runtime database-name)
    (install-schema! runtime database-name)
    (let [connection (connection database-name)
          reports (atom [])
          _ (d/listen connection ::capture #(swap! reports conj %))
          start (promise)
          invoke
          (fn []
            @start
            (transact!
             runtime database-name "receipt/concurrent"
             [{:db/id "entity" :receipt/value "parallel"}]))
          first-attempt (future (invoke))
          second-attempt (future (invoke))]
      (deliver start true)
      (let [responses [@first-attempt @second-attempt]]
        (is (every? ::protocol/success? responses))
        (is (= 1 (count (filter ::protocol/recovered? responses))))
        (is (= 1 (count @reports)))
        (is (= 1
               (d/q '[:find (count ?entity) .
                      :where [?entity :receipt/value "parallel"]]
                    (d/db connection))))))))

(deftest primary-transaction-does-not-wait-for-the-embedding-provider
  (let [base-runtime (runtime)
        database-name (str "receipt-provider-" (random-uuid))
        provider-entered (CountDownLatch. 1)
        release-provider (CountDownLatch. 1)
        provider-runtime
        (assoc base-runtime
               ::writer/embedding-inputs-for-eids
               (fn [_db-value entity-ids]
                 (mapv (fn [entity-id] {:db/id entity-id}) entity-ids))
               ::writer/embedding-assertions
               (fn [_inputs]
                 (.countDown provider-entered)
                 (.await release-provider)
                 [])
               ::writer/revalidate-embedding-assertions
               (fn [_db-value assertions] assertions))]
    (ensure-database! base-runtime database-name)
    (install-schema! base-runtime database-name)
    (let [execute (var-get (ns-resolve 'seon.db.writer 'execute-embedding!))
          worker (executor/start!
                   {::executor/name :embedding-test
                    ::executor/workers 1
                   ::executor/maximum-queued 1
                   ::executor/execute (partial execute provider-runtime)})
          provider-runtime (assoc provider-runtime
                                  ::writer/embedding-enabled? true
                                  ::writer/embedding-executor worker)]
      (try
        (let [blocked
              (future
                (transact! provider-runtime database-name "provider/blocked"
                           [{:receipt/value "blocked"}]))
              response (deref blocked 5 ::timed-out)]
          (is (not= ::timed-out response)
              "the primary commit returns before background provider work")
          (is (true? (::protocol/success? response))))
        (is (.await provider-entered 5 TimeUnit/SECONDS)
            "the committed entity is handed to background embedding")
        (let [independent
              (future
                (transact! provider-runtime database-name "provider/independent"
                           [{:receipt/value "independent"}]))
              response (deref independent 5 ::timed-out)]
          (is (not= ::timed-out response)
              "an unrelated same-database write commits during provider wait")
          (is (true? (::protocol/success? response))))
        (let [overflow
              (future
                (transact! provider-runtime database-name "provider/overflow"
                           [{:receipt/value "overflow"}]))
              response (deref overflow 5 ::timed-out)]
          (is (not= ::timed-out response)
              "a full embedding queue cannot delay the primary transaction")
          (is (true? (::protocol/success? response))))
        (finally
          (.countDown release-provider)
          (executor/stop! {::executor/executor worker})))
      (is (= #{"blocked" "independent" "overflow"}
             (set
              (map first
                   (d/q '[:find ?value
                          :where [_ :receipt/value ?value]]
                        (d/db (connection database-name))))))))))

(deftest request-id-reuse-with-different-data-is-rejected
  (let [runtime (runtime)
        database-name (str "receipt-conflict-" (random-uuid))]
    (ensure-database! runtime database-name)
    (install-schema! runtime database-name)
    (let [connection (connection database-name)
          first-response
          (transact! runtime database-name "receipt/reused"
                     [{:receipt/value "first"}])
          conflict-response
          (transact! runtime database-name "receipt/reused"
                     [{:receipt/value "different"}])]
      (is (true? (::protocol/success? first-response)))
      (is (false? (::protocol/success? conflict-response)))
      (is (= protocol/request-conflict-error
             (::protocol/error-kind conflict-response)))
      (is (nil?
           (d/q '[:find ?entity .
                  :where [?entity :receipt/value "different"]]
                (d/db connection)))))))

(deftest database-ensure-reuses-the-created-protocol-schema-without-a-write
  (let [runtime (runtime)
        database-name (str "receipt-seed-" (random-uuid))
        first-response (ensure-database! runtime database-name)
        second-response (ensure-database! runtime database-name)]
    (is (true? (::protocol/success? first-response)))
    (is (true? (::protocol/success? second-response)))
    (is (= (::protocol/coordinate first-response)
           (::protocol/coordinate second-response)))
    (is (= protocol/reserved-attributes
           (set (filter #(contains? (:schema (d/db (connection database-name))) %)
                        protocol/reserved-attributes))))))
