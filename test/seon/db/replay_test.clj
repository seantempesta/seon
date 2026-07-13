(ns seon.db.replay-test
  "Bounded transaction replay ordering, watermark, and error tests."
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

(defn- transact!
  [runtime database-name request-id transaction-data]
  (writer/handle-request
   runtime
   (protocol/transaction-request
    {::protocol/database-name database-name
     ::protocol/request-id request-id
     ::protocol/transaction-data transaction-data})))

(defn- open-database!
  []
  (let [runtime (runtime)
        database-name (str "replay-" (random-uuid))
        ensured
        (writer/handle-request
         runtime
         (protocol/ensure-database-request
          {::protocol/database-name database-name
           ::protocol/backend :memory}))
        connection
        (::registry/conn
         (registry/lookup-connection
          {::registry/database-name (keyword database-name)}))]
    (is (true? (::protocol/success? ensured)))
    {::runtime runtime
     ::database-name database-name
     ::connection connection}))

(defn- replay-page
  [connection database-name since-t through-t]
  (writer/replay-transactions-page
   (cond-> {::writer/connection connection
            ::writer/database-name database-name
            ::protocol/since-t since-t
            ::writer/page-size 1}
     (some? through-t) (assoc ::protocol/through-t through-t))))

(defn- remaining-pages
  [connection database-name first-page]
  (loop [pages [first-page]
         page first-page]
    (if (::protocol/complete? page)
      pages
      (let [next-page
            (replay-page connection database-name
                         (::protocol/continuation-t page)
                         (::protocol/through-t first-page))]
        (recur (conj pages next-page) next-page)))))

(deftest replay-is-ordered-bounded-and-preserves-retractions
  (let [{::keys [runtime database-name connection]} (open-database!)
        schema-response
        (transact!
         runtime database-name "replay/schema"
         [{:db/ident :replay/id
           :db/valueType :db.type/string
           :db/cardinality :db.cardinality/one
           :db/unique :db.unique/identity}
          {:db/ident :replay/value
           :db/valueType :db.type/string
           :db/cardinality :db.cardinality/one}])
        start-t (::protocol/basis-t schema-response)
        first-response
        (transact! runtime database-name "replay/first"
                   [{:replay/id "item" :replay/value "old"}])
        entity-id
        (d/q '[:find ?entity . :where [?entity :replay/id "item"]]
             (d/db connection))
        second-response
        (transact! runtime database-name "replay/second"
                   [[:db/retract entity-id :replay/value "old"]
                    [:db/add entity-id :replay/value "new"]])
        first-page (replay-page connection database-name start-t nil)
        watermark (::protocol/through-t first-page)
        after-watermark
        (transact! runtime database-name "replay/after-watermark"
                   [{:replay/id "later" :replay/value "excluded"}])
        pages (remaining-pages connection database-name first-page)
        events (vec (mapcat ::protocol/events pages))
        transaction-ids (mapv ::protocol/basis-t events)
        retraction-event
        (first (filter #(= "replay/second" (::protocol/request-id %)) events))
        retraction
        (first
         (filter
          (fn [[_ attribute value _ added?]]
            (and (= :replay/value attribute)
                 (= "old" value)
                 (false? added?)))
          (::protocol/transaction-data retraction-event)))]
    (is (= [(::protocol/basis-t first-response)
            (::protocol/basis-t second-response)]
           transaction-ids))
    (is (= watermark (::protocol/basis-t second-response)))
    (is (< watermark (::protocol/basis-t after-watermark))
        "later commits do not move a replay's captured upper watermark")
    (is (every? #(= watermark (::protocol/through-t %)) pages))
    (is (= ["replay/first" "replay/second"]
           (mapv ::protocol/request-id events)))
    (is (= "replay/second" (::protocol/request-id retraction-event)))
    (is (not (contains? (or (::protocol/transaction-meta retraction-event) {})
                        ::protocol/request-id))
        "event correlation is public without exposing receipt metadata")
    (is (= 1 (::protocol/datoms-retracted retraction-event)))
    (is (some? retraction))
    (is (neg? (long (nth retraction 3)))
        "history keeps Datahike's negative transaction id on retractions")
    (is (true? (::protocol/complete? (last pages))))))

(deftest replay-errors-are-canonical-and-do-not-advance-a-cursor
  (let [{::keys [runtime database-name connection]} (open-database!)
        current-t (:max-tx (d/db connection))
        missing-cursor
        (writer/handle-request
         runtime
         {::protocol/operation protocol/replay-transactions-operation
          ::protocol/database-name database-name})
        unknown-database
        (writer/handle-request
         runtime
         (protocol/replay-transactions-request
          {::protocol/database-name "missing-database"
           ::protocol/since-t current-t}))
        impossible-watermark
        (writer/handle-request
         runtime
         (protocol/replay-transactions-request
          {::protocol/database-name database-name
           ::protocol/since-t current-t
           ::protocol/through-t (inc current-t)}))]
    (is (false? (::protocol/success? missing-cursor)))
    (is (= protocol/protocol-error
           (::protocol/error-kind missing-cursor)))
    (is (false? (::protocol/success? unknown-database)))
    (is (= protocol/not-found-error
           (::protocol/error-kind unknown-database)))
    (is (false? (::protocol/success? impossible-watermark)))
    (is (= protocol/protocol-error
           (::protocol/error-kind impossible-watermark)))))
