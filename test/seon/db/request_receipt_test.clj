(ns seon.db.request-receipt-test
  "Durable transaction request receipt and recovery tests."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [datahike.api :as d]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer]))

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
   ::writer/transaction-transform (fn [_db-value transaction-data]
                                    transaction-data)
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

(deftest database-ensure-does-not-reseed-converged-receipt-schema
  (let [runtime (runtime)
        database-name (str "receipt-seed-" (random-uuid))
        first-response (ensure-database! runtime database-name)
        second-response (ensure-database! runtime database-name)]
    (is (true? (::protocol/success? first-response)))
    (is (true? (::protocol/success? second-response)))
    (is (= (::protocol/coordinate first-response)
           (::protocol/coordinate second-response)))
    (is (= (set (map :db/ident protocol/receipt-schema))
           (set (filter #(contains? (:schema (d/db (connection database-name))) %)
                        (map :db/ident protocol/receipt-schema)))))))
