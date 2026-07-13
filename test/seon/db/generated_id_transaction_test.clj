(ns seon.db.generated-id-transaction-test
  "Writer-level tests for atomic generated identity allocation."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [datahike.api :as d]
            [seon.db.id :as id]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer]))

(def ^:private schema-transaction
  [{:db/ident :generated.thing/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :generated.thing/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :generated.thing/other
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :generated.other/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :generated.parent/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :generated.parent/child
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/isComponent true}
   {:db/ident :generated.child/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :generated.child/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.schema/key
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.db.id/generator
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])

(def ^:private generator-policies
  [{:seon.schema/key :generated.thing/id
    :seon.db.id/generator :seon.db.id.generator/compact}
   {:seon.schema/key :generated.other/id
    :seon.db.id/generator :seon.db.id.generator/compact}
   {:seon.schema/key :generated.child/id
    :seon.db.id/generator :seon.db.id.generator/compact}])

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
  {::writer/database-initializer
   (fn [connection _database-name]
     (d/transact connection schema-transaction)
     (d/transact connection generator-policies))
   ::writer/transaction-transform (fn [_db-value transaction-data]
                                    transaction-data)
   ::writer/knn-search (fn [_db-value _request] {:seon.embed/hits []})
   ::writer/publisher
   {::uds/channel (Object.)
    ::uds/subscribers (atom #{})
    ::uds/closed? (atom false)}})

(defn- open-database!
  []
  (let [runtime (runtime)
        database-name (str "generated-" (random-uuid))
        response
        (writer/handle-request
         runtime
         (protocol/ensure-database-request
          {::protocol/database-name database-name
           ::protocol/backend :memory}))]
    (is (true? (::protocol/success? response)))
    {::runtime runtime
     ::database-name database-name
     ::connection
     (::registry/conn
      (registry/lookup-connection
       {::registry/database-name (keyword database-name)}))}))

(defn- compact-value
  [label]
  (subs (str "x" (str/replace label #"[^a-z0-9]" "0") "00000000000")
        0 12))

(defn- candidate
  [key identity-attribute value]
  {::id/key key
   ::id/identity-attr identity-attribute
   ::id/value value})

(defn- allocate!
  [runtime database-name request-id transaction-data candidates]
  (writer/handle-request
   runtime
   (protocol/transaction-request
    {::protocol/database-name database-name
     ::protocol/request-id request-id
     ::protocol/transaction-data transaction-data
     ::protocol/generated-candidates candidates})))

(deftest multiple-generated-identities-commit-with-their-relationships
  (let [{::keys [runtime database-name connection]} (open-database!)
        thing-value (compact-value "thing")
        other-value (compact-value "other")
        thing-candidate
        (candidate :allocation/thing :generated.thing/id thing-value)
        other-candidate
        (candidate :allocation/other :generated.other/id other-value)
        response
        (allocate!
         runtime database-name "generated/relationship"
         [{:db/id "thing-temp"
           :generated.thing/id thing-value
           :generated.thing/name "Thing"
           :generated.thing/other "other-temp"}
          {:db/id "other-temp"
           :generated.other/id other-value}]
         [thing-candidate other-candidate])
        generated (::protocol/generated-entity-ids response)
        thing-id (:allocation/thing generated)
        other-id (:allocation/other generated)
        stored
        (d/pull (d/db connection) '[*]
                [:generated.thing/id thing-value])]
    (is (true? (::protocol/success? response)))
    (is (every? pos-int? [thing-id other-id]))
    (is (not= thing-id other-id))
    (is (= other-id (get-in stored [:generated.thing/other :db/id])))
    (is (= {"thing-temp" thing-id "other-temp" other-id}
           (select-keys (::protocol/temporary-ids response)
                        ["thing-temp" "other-temp"])))))

(deftest nested-component-can-own-a-generated-identity
  (let [{::keys [runtime database-name connection]} (open-database!)
        child-value (compact-value "nested-child")
        child-candidate
        (candidate :allocation/child :generated.child/id child-value)
        response
        (allocate!
         runtime database-name "generated/nested"
         [{:generated.parent/id "known-parent"
           :generated.parent/child
           {:generated.child/id child-value
            :generated.child/name "Nested"}}]
         [child-candidate])
        child-id
        (get (::protocol/generated-entity-ids response) :allocation/child)
        parent
        (d/pull (d/db connection) '[*]
                [:generated.parent/id "known-parent"])]
    (is (true? (::protocol/success? response)))
    (is (pos-int? child-id))
    (is (= child-id (get-in parent [:generated.parent/child :db/id])))
    (is (= "Nested"
           (:generated.child/name
            (d/pull (d/db connection) '[*] child-id))))))

(deftest candidate-conflicts-are-atomic-under-concurrency
  (let [{::keys [runtime database-name connection]} (open-database!)
        value (compact-value "contended")
        attempted (candidate :allocation/thing :generated.thing/id value)
        start (promise)
        invoke
        (fn [label]
          @start
          (allocate!
           runtime database-name (str "generated/concurrent/" label)
           [{:generated.thing/id value
             :generated.thing/name label}]
           [attempted]))
        first-attempt (future (invoke "first"))
        second-attempt (future (invoke "second"))]
    (deliver start true)
    (let [responses [@first-attempt @second-attempt]
          winner (first (filter ::protocol/success? responses))
          conflict (first (remove ::protocol/success? responses))]
      (is (some? winner))
      (is (= protocol/generated-candidate-conflict-error
             (::protocol/error-kind conflict)))
      (is (= attempted (::protocol/generated-candidate conflict)))
      (is (= 1
             (count (d/datoms (d/db connection) :avet
                              :generated.thing/id value)))))))

(deftest generated-manifest-is-part-of-the-durable-request-fingerprint
  (let [{::keys [runtime database-name]} (open-database!)
        value (compact-value "fingerprint")
        request-id "generated/fingerprint"
        original
        (candidate :allocation/thing :generated.thing/id value)
        changed (assoc original ::id/key :allocation/renamed)
        first-response
        (allocate! runtime database-name request-id
                   [{:generated.thing/id value}] [original])
        recovered-response
        (allocate! runtime database-name request-id
                   [{:generated.thing/id value}] [original])
        conflict-response
        (allocate! runtime database-name request-id
                   [{:generated.thing/id value}] [changed])]
    (is (true? (::protocol/success? first-response)))
    (is (true? (::protocol/recovered? recovered-response)))
    (is (= (::protocol/generated-entity-ids first-response)
           (::protocol/generated-entity-ids recovered-response)))
    (is (false? (::protocol/success? conflict-response)))
    (is (= protocol/request-conflict-error
           (::protocol/error-kind conflict-response)))))
