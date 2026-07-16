(ns seon.db.protocol-test
  "Closed transport-neutral database protocol tests."
  (:require [clojure.test :refer [deftest is testing]]
            [cognitect.transit :as transit]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(def ^:private db
  {:db-name "default"
   :t 536870929
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id
   #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"})

(def ^:private other-db
  (assoc db
         :db-name "research"
         :datahike/commit-id
         #uuid "b6d0f53b-3044-5f0a-95d8-5ea3218248f5"))

(def ^:private datom [17 :person/name "Ada" 536870929 true])

(defrecord HostOwner [value])

(defn- transit-roundtrip
  [value]
  (let [output (ByteArrayOutputStream.)]
    (transit/write (transit/writer output :json) value)
    (transit/read
     (transit/reader (ByteArrayInputStream. (.toByteArray output)) :json))))

(deftest database-values-are-complete-closed-and-temporally-unambiguous
  (is (= 9 protocol/current-version))
  (is (protocol/database-value? db))
  (is (protocol/database-value? (assoc db :as-of 536870928)))
  (is (protocol/database-value? (assoc db :since #inst "2026-07-16")))
  (doseq [invalid [(dissoc db :t)
                   (assoc db :extra true)
                   (assoc db :as-of 536870928 :since 536870927)
                   (assoc db :history :yes)]]
    (is (false? (protocol/database-value? invalid)))))

(deftest reads-carry-ordinary-database-values-without-legacy-routing
  (let [descriptor-shaped-data [[other-db]]
        query (protocol/query-request
               {::protocol/request-id "query/multi"
                :seon.db/db db
                ::protocol/query-form
                '[:find ?value :in $left $right [[?value]]
                  :where [$left _ :person/name]
                         [$right _ :person/name]]
                ::protocol/arguments [db other-db descriptor-shaped-data]})
        pull (protocol/pull-request
              {::protocol/request-id "pull/one"
               :seon.db/db db
               ::protocol/selector '[*]
               ::protocol/entity-id 17})]
    (is (every? protocol/valid-request? [query pull]))
    (is (= descriptor-shaped-data
           (nth (::protocol/arguments query) 2)))
    (is (= query (transit-roundtrip query)))
    (doseq [legacy [(assoc query ::protocol/database-name "default")
                    (assoc query ::protocol/coordinate {})
                    (assoc query ::protocol/attachment {})
                    (assoc query ::protocol/history? true)]]
      (is (false? (protocol/valid-request? legacy))))
    (is (false?
         (protocol/valid-request?
          (assoc query :seon.db/db
                 (assoc db :as-of 536870928 :since 536870927)))))))

(deftest index-pages-use-datahikes-native-eager-shape
  (let [request (protocol/index-page-request
                 {::protocol/request-id "index/page"
                  :seon.db/db db
                  ::protocol/index :aevt
                  ::protocol/prefix [:person/name]
                  ::protocol/direction :forward
                  ::protocol/limit 20
                  ::protocol/cursor datom})
        response (protocol/success
                  {::protocol/request-id "index/page"
                   :datahike.index-page/datoms [datom]
                   :datahike.index-page/complete? false
                   :datahike.index-page/cursor datom})]
    (is (protocol/valid-request? request))
    (is (protocol/valid-response? response))
    (is (= response (transit-roundtrip response)))
    (is (false? (protocol/valid-response?
                 (assoc response ::protocol/datoms [datom]))))
    (is (false? (protocol/valid-request?
                 (assoc request ::protocol/cursor [17 :person/name]))))))

(deftest execute-many-members-select-their-own-database-values
  (let [request (protocol/execute-many-request
                 {::protocol/request-id "many/databases"
                  ::protocol/members
                  [{::protocol/operation protocol/schema-operation
                    :seon.db/db db}
                   {::protocol/operation protocol/pull-operation
                    :seon.db/db other-db
                    ::protocol/selector [:db/id]
                    ::protocol/entity-id 17}]})
        response (protocol/success
                  {::protocol/request-id "many/databases"
                   ::protocol/results [{:person/name "Ada"} nil]})]
    (is (protocol/valid-request? request))
    (is (= protocol/maximum-frame-bytes
           (:datahike.resource/max-result-weight request)))
    (is (protocol/valid-response? response))
    (is (false? (protocol/valid-request?
                 (dissoc request :datahike.resource/max-result-weight))))
    (is (false? (protocol/valid-request?
                 (assoc request ::protocol/database-name "default"))))))

(deftest transactions-and-listeners-carry-one-native-report-shape
  (let [expected-db (assoc db :t 536870928)
        request (protocol/transaction-request
                 {::protocol/request-id "transact/native"
                  :seon.db/db db
                  :seon.db/expected-db expected-db
                  ::protocol/transaction-data
                  [{:db/id 17 :person/name "Ada"}]})
        report {:db-before expected-db
                :db-after db
                :tx-data [datom]
                :tempids {-1 17}
                :tx-meta {:db/txInstant #inst "2026-07-16"}}
        response (protocol/success
                  (assoc report ::protocol/request-id "transact/native"))
        event (assoc report
                     ::protocol/event protocol/datoms-event
                     ::protocol/request-id "listen/native")
        resynchronization
        {::protocol/event protocol/resynchronization-event
         ::protocol/request-id "listen/native"
         :db-after db}
        listen (protocol/listen-request
                {::protocol/request-id "listen/native"
                 :seon.db/db db
                 ::protocol/datom-patterns [{:seon.db/a :person/name}]})]
    (is (every? protocol/valid-request? [request listen]))
    (is (every? protocol/valid-response?
                [response event resynchronization]))
    (doseq [legacy [(assoc request ::protocol/expected-coordinate {})
                    (assoc response ::protocol/previous-coordinate {})
                    (assoc response ::protocol/datoms-added 1)
                    (assoc response ::protocol/datoms-retracted 0)]]
      (is (false? ((if (::protocol/operation legacy)
                     protocol/valid-request?
                     protocol/valid-response?)
                   legacy))))
    (testing "transaction and listener selection require current values"
      (is (false? (protocol/valid-request?
                   (assoc request :seon.db/db
                          (assoc db :as-of 536870928)))))
      (is (false? (protocol/valid-request?
                   (assoc request :seon.db/expected-db
                          (assoc expected-db :history true)))))
      (is (false? (protocol/valid-request?
                   (assoc listen :seon.db/db
                          (assoc db :since 536870928))))))))

(deftest release-selects-an-acquired-database-by-ordinary-value
  (let [request (protocol/release-database-request
                 {::protocol/request-id "release/research"
                  :seon.db/db other-db})
        response (protocol/success
                  {::protocol/request-id "release/research"
                   ::protocol/released? true})]
    (is (protocol/valid-request? request))
    (is (protocol/valid-response? response))
    (is (false? (protocol/valid-request?
                 (assoc request ::protocol/target-attachment {}))))))

(deftest private-branch-administration-retains-native-coordinates
  (let [point {::coordinate/database-id
               #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
               ::coordinate/branch :db
               ::coordinate/commit-id
               #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"
               ::coordinate/t 536870929}
        request (protocol/resolve-transaction-coordinate-request
                 {::protocol/request-id "restore/tx"
                  ::protocol/database-name "default"
                  ::protocol/head-coordinate
                  {::coordinate/database-id (::coordinate/database-id point)
                   ::coordinate/branch :db
                   ::coordinate/commit-id (::coordinate/commit-id point)
                   ::coordinate/t (::coordinate/t point)}
                  ::protocol/transaction-id 536870929})]
    (is (protocol/valid-request? request))))

(deftest ordinary-wire-values-reject-host-owners-and-lazy-results
  (is (every? protocol/ordinary-wire-value?
              [nil true 17 17N 17M 1/3 "value" :qualified/value
               #inst "2026-07-16" db [db] #{:one :two}]))
  (is (not-any? protocol/ordinary-wire-value?
                [(fn [] :host) (map identity [1 2]) (->HostOwner :host)
                 (atom :host) (future :host) (promise)
                 (Thread/currentThread) (ex-info "host" {})]))
  (let [response (protocol/success
                  {::protocol/request-id "pull/lazy"
                   ::protocol/result (map identity [1 2])})]
    (is (false? (protocol/valid-response? response)))
    (is (map? (protocol/explain-response response)))))

(deftest generated-candidates-remain-closed-and-uniquely-keyed
  (let [candidate {:seon.db.id/key :allocation/agent
                   :seon.db.id/identity-attr :seon.agent/id
                   :seon.db.id/value "mint-ember-otter"
                   :seon.db.id/dependent-lookup-refs
                   [[:seon.user/id "human"]]}
        request (protocol/transaction-request
                 {::protocol/request-id "transact/generated"
                  :seon.db/db db
                  ::protocol/transaction-data []
                  ::protocol/generated-candidates [candidate]})]
    (is (protocol/valid-request? request))
    (testing "candidate keys identify one result position"
      (is (false?
           (protocol/valid-request?
            (assoc request ::protocol/generated-candidates
                   [candidate
                    (assoc candidate
                           :seon.db.id/identity-attr :my.plan/id
                           :seon.db.id/value "abcdefghijkl")])))))))
