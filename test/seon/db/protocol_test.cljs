(ns seon.db.protocol-test
  "Cross-host ordinary database-value protocol proofs."
  (:require [cljs.test :refer [deftest is testing]]
            [cognitect.transit :as transit]
            [seon.db.protocol :as protocol]
            [seon.schema :as schema]))

(def ^:private db
  {:db-name "default"
   :store-id [#uuid "6a56b426-c836-5817-9f6b-20584f2e81d5" :db]
   :t 536870929
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id
   #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"})

(def ^:private other-db
  (assoc db
         :db-name "research"
         :store-id [#uuid "b6d0f53b-3044-5f0a-95d8-5ea3218248f5" :db]
         :datahike/commit-id
         #uuid "b6d0f53b-3044-5f0a-95d8-5ea3218248f5"))

(defrecord HostOwner [value])

(deftest initialization-pages-preserve-a-precomputed-vector-exactly
  (let [artifact-digest (apply str (repeat 64 "a"))
        config-manifest-digest (apply str (repeat 64 "b"))
        fingerprint "precomputed-initialization"
        pages
        [{:seon.db.initialization/fingerprint fingerprint
          :seon.db.initialization/page-index 0
          :seon.db.initialization/page-count 2
          :seon.db.initialization/page-rows 256
          :seon.db.initialization/phase
          :seon.db.initialization.phase/schema
          :seon.db/program []}
         {:seon.db.initialization/fingerprint fingerprint
          :seon.db.initialization/page-index 1
          :seon.db.initialization/page-count 2
          :seon.db.initialization/page-rows 256
          :seon.db.initialization/phase
          :seon.db.initialization.phase/completion}]
        precomputed
        {:seon.execution/artifact-digest artifact-digest
         :seon.db.initialization/config-manifest-digest
         config-manifest-digest
         :seon.db/initialization-pages pages}
        raw
        {:seon.execution/artifact-digest artifact-digest
         :seon.db.initialization/config-manifest-digest
         config-manifest-digest
         :seon.db.initialization/page-rows 256
         :seon.db/attributes []
         :seon.db/program []
         :seon.db/initial-data []}]
    (is (schema/valid-candidate-value?
         :seon.db/precomputed-initialization precomputed))
    (is (schema/valid-candidate-value? :seon.db/raw-initialization raw))
    (is (every? #(schema/valid-candidate-value?
                  :seon.db/initialization %)
                [raw precomputed]))
    (is (identical? pages (protocol/initialization-pages precomputed))
        "precomputed pages cross the protocol boundary without reconstruction")))

(deftest initialization-state-shape-carries-optional-applied-identity
  (let [release-digest (apply str (repeat 64 "c"))
        config-manifest-digest (apply str (repeat 64 "d"))
        initialization-state
        {:seon.db.initialization/id "database"
         :seon.db.initialization/fingerprint "initialization-fingerprint"
         :seon.db.initialization/page-count 12
         :seon.db.initialization/status
         :seon.db.initialization.status/complete}
        applied-state
        (assoc initialization-state
               :seon.db.initialization/release-digest release-digest
               :seon.db.initialization/config-manifest-digest
               config-manifest-digest)]
    (is (schema/valid-candidate-value?
         :seon.db.initialization/entity initialization-state)
        "an in-progress or legacy receipt does not claim applied identity")
    (is (schema/valid-candidate-value?
         :seon.db.initialization/entity applied-state))
    (is (false?
         (schema/valid-candidate-value?
          :seon.db.initialization/entity
          (assoc applied-state
                 :seon.db.initialization/release-digest "not-a-digest"))))
    (is (false?
         (schema/valid-candidate-value?
          :seon.db.initialization/entity
          (assoc applied-state
                 :seon.db.initialization/config-manifest-digest
                 "not-a-digest"))))))

(deftest tempid-receipts-name-string-and-integer-alternatives
  (let [[string-receipt int-receipt]
        (protocol/tempid-receipts "receipt/named" ["person" -1])]
    (is (= "person" (:seon.db.protocol.tempid/string-key string-receipt)))
    (is (not (contains? string-receipt :seon.db.protocol.tempid/int-key)))
    (is (= -1 (:seon.db.protocol.tempid/int-key int-receipt)))
    (is (not (contains? int-receipt :seon.db.protocol.tempid/string-key)))
    (is (every? #(not (contains? % :seon.db.protocol.tempid/key-edn))
                [string-receipt int-receipt]))))

(def ^:private transit-writer (transit/writer :json))

(defn- transit-bytes
  [value]
  (.-byteLength (.encode (js/TextEncoder.)
                         (transit/write transit-writer value))))

(defn- session-shapes
  []
  {:request
   (protocol/session-open-request
    {::protocol/maximum-frame-bytes protocol/maximum-frame-bytes})
   :success
   (protocol/session-open-success
    {::protocol/configured-maximum-frame-bytes 1048576
     ::protocol/maximum-frame-bytes 1048576})
   :incompatible-version
   (protocol/incompatible-version-failure {::protocol/peer-version 12})
   :connection-capacity
   (protocol/connection-capacity-failure
    {::protocol/maximum-connections 12})
   :session-open-required
   (protocol/session-open-required-failure
    {::protocol/request-id "ordinary/request"})
   :oversized-inbound
   (protocol/frame-too-large-failure
    {::protocol/request-id protocol/session-control-request-id
     ::protocol/maximum-frame-bytes 65536})
   :oversized-response
   (protocol/frame-too-large-failure
    {::protocol/request-id "ordinary/request"
     ::protocol/maximum-frame-bytes 65536})})

(deftest session-opening-shapes-fit-the-fixed-cross-host-bootstrap-ceiling
  (is (= 14 protocol/current-version))
  (doseq [[shape value] (session-shapes)]
    (is ((if (= shape :request)
           protocol/valid-request?
           protocol/valid-response?)
         value)
        (str shape " is canonical protocol data"))
    (is (< (transit-bytes value)
           protocol/session-open-maximum-frame-bytes)
        (str shape " fits the fixed session-open ceiling"))))

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
                 (assoc request ::protocol/branch-head {}))))))

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
                   :datahike.read/dependency-plan :all
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
    (doseq [legacy [(assoc response ::protocol/datoms-added 1)]]
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
