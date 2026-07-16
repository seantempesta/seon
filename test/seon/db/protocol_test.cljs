(ns seon.db.protocol-test
  "Cross-host ordinary-value and generated-candidate protocol proofs."
  (:require [cljs.test :refer [deftest is testing]]
            [cognitect.transit :as transit]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]))

(def ^:private point
  {::coordinate/database-id
   #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
   ::coordinate/branch :db
   ::coordinate/commit-id
   #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"
   ::coordinate/t 536870929})

(def ^:private attachment (coordinate/attachment point))

(defrecord HostOwner [value])

(deftest ordinary-wire-values-are-eager-portable-data
  (let [accepted
        [nil true 17 "value" :bare :qualified/value 'query/value
         #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
         (js/Date. "2026-07-16T00:00:00.000Z")
         (transit/integer "9007199254740993")
         (transit/bigint "9007199254740993123456789")
         (transit/bigdec "17.25")
         (transit/uri "https://example.test/value")
         (transit/binary "AQID")
         (transit/tagged-value
          "ratio"
          [(transit/integer "9007199254740993")
           (transit/integer "9007199254740995")])
         {:find ['?entity] :where [['?entity :person/name "Ada"]]}
         #{:one :two} [:one {:bare "preserved"}] '(and (= ?x 1))]
        rejected
        [(fn [] :host) (map identity [1 2]) (->HostOwner :host)
         (atom :host) (delay :host) (js/Promise.resolve :host)
         (js/Error. "host") #js {:write (fn [_] true)}]]
    (is (every? protocol/ordinary-wire-value? accepted))
    (is (not-any? protocol/ordinary-wire-value? rejected))))

(deftest canonical-validation-rejects-cljs-host-owners
  (let [query
        (protocol/query-request
         {::protocol/request-id "query/promise"
          ::protocol/database-name "default"
          ::protocol/attachment attachment
          ::protocol/coordinate point
          ::protocol/query-form '[:find ?value :in $ ?input]
          ::protocol/arguments [(js/Promise.resolve :host)]})
        response
        (protocol/success
         {::protocol/request-id "pull/lazy-result"
          ::protocol/database-name "default"
          ::protocol/attachment attachment
          ::protocol/coordinate point
          ::protocol/result (map identity [1 2])})
        event
        {::protocol/event protocol/datoms-event
         ::protocol/request-id "listen/socket"
         ::protocol/coordinate point
         ::protocol/datoms
         [{:seon.db/e 17
           :seon.db/a :person/name
           :seon.db/v #js {:write (fn [_] true)}
           :seon.db/tx 536870929
           :seon.db/added? true}]}]
    (is (false? (protocol/valid-request? query)))
    (is (map? (protocol/explain-request query)))
    (is (false? (protocol/valid-response? response)))
    (is (map? (protocol/explain-response response)))
    (is (false? (protocol/valid-response? event)))))

(deftest generated-candidate-manifests-have-one-portable-shape
  (let [candidate
        {:seon.db.id/key :allocation/agent
         :seon.db.id/identity-attr :seon.agent/id
         :seon.db.id/value "mint-ember-otter"
         :seon.db.id/dependent-lookup-refs
         [[:seon.user/id "human"]]}
        request
        (protocol/transaction-request
         {::protocol/database-name "default"
          ::protocol/request-id "transact/generated"
          ::protocol/transaction-data []
          ::protocol/generated-candidates [candidate]})]
    (is (protocol/valid-request? request))
    (testing "closed candidate maps reject malformed manifests"
      (doseq [invalid-candidate
              [(assoc candidate :seon.db.id/extra true)
               (assoc candidate :seon.db.id/key :unqualified)
               (assoc candidate :seon.db.id/value "invalid")
               (assoc candidate :seon.db.id/dependent-lookup-refs [])]]
        (is (false?
             (protocol/valid-request?
              (assoc request ::protocol/generated-candidates
                     [invalid-candidate]))))))
    (is (false?
         (protocol/valid-request?
          (assoc request ::protocol/generated-candidates
                 [candidate
                  (assoc candidate
                         :seon.db.id/identity-attr :my.plan/id
                         :seon.db.id/value "abcdefghijkl")]))))))
