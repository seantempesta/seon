(ns seon.db.protocol-test
  "Cross-host ordinary database-value protocol proofs."
  (:require [cljs.test :refer [deftest is testing]]
            [cognitect.transit :as transit]
            [seon.db.protocol :as protocol]))

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

(defrecord HostOwner [value])

(deftest database-values-have-one-cross-host-ordinary-shape
  (is (protocol/database-value? db))
  (is (protocol/database-value? (assoc db :as-of 536870928)))
  (doseq [invalid [(dissoc db :history)
                   (assoc db :extra true)
                   (assoc db :as-of 536870928 :since 536870927)]]
    (is (false? (protocol/database-value? invalid)))))

(deftest query-database-values-remain-in-their-top-level-input-positions
  (let [ordinary [[other-db]]
        request (protocol/query-request
                 {::protocol/request-id "query/cljs"
                  :seon.db/db db
                  ::protocol/query-form
                  '[:find ?value :in $left $right [[?value]]
                    :where [$left _ :person/name]
                           [$right _ :person/name]]
                  ::protocol/arguments [db other-db ordinary]})]
    (is (protocol/valid-request? request))
    (is (= [db other-db ordinary] (::protocol/arguments request)))
    (is (false? (protocol/valid-request?
                 (assoc request ::protocol/coordinate {}))))))

(deftest execute-many-members-carry-independent-database-values
  (let [input {::protocol/request-id "many/cljs"
               ::protocol/members
               [{::protocol/operation protocol/pull-operation
                 :seon.db/db db
                 ::protocol/selector [:db/id]
                 ::protocol/entity-id 1}
                {::protocol/operation protocol/schema-operation
                 :seon.db/db other-db}]}
        default-request (protocol/execute-many-request input)
        smaller-request
        (protocol/execute-many-request
         (assoc input :datahike.resource/max-result-weight 1024))]
    (is (protocol/valid-request? default-request))
    (is (= protocol/maximum-frame-bytes
           (:datahike.resource/max-result-weight default-request)))
    (is (= 1024 (:datahike.resource/max-result-weight smaller-request)))
    (is (false? (protocol/valid-request?
                 (assoc default-request
                        ::protocol/database-name "default"))))))

(deftest nested-pull-results-remain-arbitrary-ordinary-data
  (let [result {:person/name "Ada"
                :person/contact {:contact/email "ada@example.test"}
                :person/peers [{:person/name "Grace"}
                               {:person/name "Edsger"}]
                :person/database-shaped-data other-db}
        response (protocol/success
                  {::protocol/request-id "pull/nested"
                   ::protocol/result result})]
    (is (protocol/valid-response? response))
    (is (= result (::protocol/result response)))))

(deftest native-transaction-reports-are-ordinary-cross-host-data
  (let [before (assoc db :t 536870928)
        datom [17 :person/name "Ada" 536870929 true]
        request (protocol/transaction-request
                 {::protocol/request-id "transact/cljs"
                  :seon.db/db db
                  :seon.db/expected-db before
                  ::protocol/transaction-data []})
        response (protocol/success
                  {::protocol/request-id "transact/cljs"
                   :db-before before
                   :db-after db
                   :tx-data [datom]
                   :tempids {-1 17}
                   :tx-meta {:db/txInstant (js/Date.)}})]
    (is (protocol/valid-request? request))
    (is (protocol/valid-response? response))
    (is (false? (protocol/valid-request?
                 (assoc request :seon.db/db
                        (assoc db :history true)))))
    (doseq [legacy [(assoc request ::protocol/expected-coordinate {})
                    (assoc response ::protocol/previous-coordinate {})
                    (assoc response ::protocol/datoms-added 1)]]
      (is (false? ((if (::protocol/operation legacy)
                     protocol/valid-request?
                     protocol/valid-response?)
                   legacy))))))

(deftest ordinary-wire-values-reject-cljs-host-owners
  (let [accepted
        [nil true 17 "value" :qualified/value 'query/value db
         (js/Date. "2026-07-16T00:00:00.000Z")
         (transit/integer "9007199254740993")
         (transit/bigint "9007199254740993123456789")
         (transit/bigdec "17.25")
         (transit/uri "https://example.test/value")
         (transit/binary "AQID")
         (transit/tagged-value
          "ratio"
          [(transit/integer "9007199254740993")
           (transit/integer "9007199254740995")])]
        rejected
        [(fn [] :host) (map identity [1 2]) (->HostOwner :host)
         (atom :host) (delay :host) (js/Promise.resolve :host)
         (js/Error. "host") #js {:write (fn [_] true)}]]
    (is (every? protocol/ordinary-wire-value? accepted))
    (is (not-any? protocol/ordinary-wire-value? rejected))))

(deftest generated-candidates-remain-closed-and-uniquely-keyed
  (let [candidate
        {:seon.db.id/key :allocation/agent
         :seon.db.id/identity-attr :seon.agent/id
         :seon.db.id/value "mint-ember-otter"
         :seon.db.id/dependent-lookup-refs [[:seon.user/id "human"]]}
        request
        (protocol/transaction-request
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
