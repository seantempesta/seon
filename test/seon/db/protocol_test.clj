(ns seon.db.protocol-test
  "Closed transport-neutral database protocol tests."
  (:require [clojure.test :refer [deftest is testing]]
            [cognitect.transit :as transit]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(def ^:private point
  {::coordinate/database-id
   #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
   ::coordinate/branch :db
   ::coordinate/commit-id
   #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"
   ::coordinate/t 536870929})

(def ^:private attachment (coordinate/attachment point))

(def ^:private datom
  {:seon.db/e 17
   :seon.db/a :person/name
   :seon.db/v "Ada"
   :seon.db/tx 536870929
   :seon.db/added? true})

(def ^:private cursor
  (merge datom
         {::protocol/coordinate point
          ::protocol/index :aevt
          ::protocol/direction :forward
          ::protocol/history? false}))

(defn- transit-roundtrip
  [value]
  (let [output (ByteArrayOutputStream.)]
    (transit/write (transit/writer output :json) value)
    (transit/read
     (transit/reader (ByteArrayInputStream. (.toByteArray output)) :json))))

(deftest schema-and-history-requests-are-closed-and-transit-stable
  (let [schema-request
        (protocol/schema-request
         {::protocol/request-id "schema/read"
          ::protocol/database-name "default"
          ::protocol/attachment attachment
          ::protocol/coordinate point})
        schema-response
        (protocol/success
         {::protocol/request-id "schema/read"
          ::protocol/database-name "default"
          ::protocol/attachment attachment
          ::protocol/coordinate point
          ::protocol/schema
          {:person/name {:db/valueType :db.type/string
                         :db/cardinality :db.cardinality/one}}})
        query-request
        (protocol/query-request
         {::protocol/request-id "query/history"
          ::protocol/database-name "default"
          ::protocol/attachment attachment
          ::protocol/coordinate point
          ::protocol/query-form '[:find ?e :where [?e :person/name]]
          ::protocol/arguments []
          ::protocol/history? true})]
    (is (= 6 protocol/current-version))
    (is (every? protocol/valid-request? [schema-request query-request]))
    (is (protocol/valid-response? schema-response))
    (is (= schema-request (transit-roundtrip schema-request)))
    (is (= schema-response (transit-roundtrip schema-response)))
    (is (false? (protocol/valid-request?
                 (assoc schema-request :transport/session-id "private"))))
    (is (false? (protocol/valid-response?
                 (assoc schema-response :transport/cache-owner "private"))))
    (is (false? (protocol/valid-request?
                 (assoc query-request ::protocol/history? :yes))))))

(deftest index-pages-seal-the-complete-datom-and-ordered-view
  (let [request
        (protocol/index-page-request
         {::protocol/request-id "index/page"
          ::protocol/database-name "default"
          ::protocol/attachment attachment
          ::protocol/coordinate point
          ::protocol/index :aevt
          ::protocol/prefix [:person/name]
          ::protocol/direction :forward
          ::protocol/limit 200
          ::protocol/cursor cursor})
        response
        (protocol/success
         {::protocol/request-id "index/page"
          ::protocol/database-name "default"
          ::protocol/attachment attachment
          ::protocol/coordinate point
          ::protocol/datoms [datom]
          ::protocol/complete? false
          ::protocol/cursor cursor})]
    (is (protocol/valid-request? request))
    (is (protocol/valid-response? response))
    (is (= request (transit-roundtrip request)))
    (is (= response (transit-roundtrip response)))
    (testing "the semantic page and prefix bounds are closed"
      (is (false? (protocol/valid-request?
                   (assoc request ::protocol/limit 0))))
      (is (false? (protocol/valid-request?
                   (assoc request ::protocol/limit 201))))
      (is (false? (protocol/valid-request?
                   (assoc request ::protocol/prefix [1 2 3 4 5])))))
    (testing "a cursor cannot move to another ordered view"
      (doseq [mismatched
              [(assoc-in request [::protocol/cursor ::protocol/coordinate]
                         (assoc point ::coordinate/t 536870928))
               (assoc request ::protocol/index :avet)
               (assoc request ::protocol/direction :reverse)
               (assoc request ::protocol/history? true)]]
        (is (false? (protocol/valid-request? mismatched)))
        (is (map? (protocol/explain-request mismatched)))))
    (is (false? (protocol/valid-response?
                 (update response ::protocol/cursor dissoc :seon.db/added?))))))

(deftest byte-valued-prefix-and-cursor-survive-transit-as-content
  (let [value (byte-array [1 2 3])
        request
        (protocol/index-page-request
         {::protocol/request-id "index/bytes"
          ::protocol/database-name "default"
          ::protocol/attachment attachment
          ::protocol/coordinate point
          ::protocol/index :avet
          ::protocol/prefix [:fingerprint value]
          ::protocol/direction :forward
          ::protocol/limit 10
          ::protocol/cursor
          (merge cursor
                 {:seon.db/a :fingerprint
                  :seon.db/v value
                  ::protocol/index :avet})})
        decoded (transit-roundtrip request)]
    (is (protocol/valid-request? decoded))
    (is (= [1 2 3]
           (vec (second (::protocol/prefix decoded)))
           (vec (get-in decoded [::protocol/cursor :seon.db/v]))))))

(deftest execute-many-composes-only-immutable-read-members
  (let [request
        (protocol/execute-many-request
         {::protocol/request-id "many/schema-index"
          ::protocol/database-name "default"
          ::protocol/attachment attachment
          ::protocol/coordinate point
          ::protocol/members
          [{::protocol/operation protocol/schema-operation}
           {::protocol/operation protocol/index-page-operation
            ::protocol/index :aevt
            ::protocol/prefix [:person/name]
            ::protocol/direction :forward
            ::protocol/limit 20
            ::protocol/cursor cursor}]})
        response
        (protocol/success
         {::protocol/request-id "many/schema-index"
          ::protocol/database-name "default"
          ::protocol/attachment attachment
          ::protocol/coordinate point
          ::protocol/results
          [(protocol/success {::protocol/schema {}})
           (protocol/success {::protocol/datoms [datom]
                              ::protocol/complete? false
                              ::protocol/cursor cursor})]})]
    (is (protocol/valid-request? request))
    (is (protocol/valid-response? response))
    (is (= response (transit-roundtrip response)))
    (is (false? (protocol/valid-request?
                 (assoc-in request
                           [::protocol/members 1 ::protocol/direction]
                           :reverse))))
    (doseq [operation [protocol/listen-operation
                       protocol/unlisten-operation]]
      (is (false?
           (protocol/valid-request?
            (assoc request ::protocol/members
                   [{::protocol/operation operation}])))))))

(deftest selective-interests-use-request-identity-and-closed-data
  (let [query-listen
        (protocol/listen-request
         {::protocol/request-id "listen/query"
          ::protocol/database-name "default"
          ::protocol/attachment attachment
          ::protocol/query-form
          '[:find ?name :where [?entity :person/name ?name]]})
        datom-listen
        (protocol/listen-request
         {::protocol/request-id "listen/datoms"
          ::protocol/database-name "default"
          ::protocol/attachment attachment
          ::protocol/datom-patterns
          [{:seon.db/a :seon.agent.message/to
            :seon.db/e 17
            :seon.db/added? true}]})
        unlisten
        (protocol/unlisten-request
         {::protocol/request-id "unlisten/query"
          ::protocol/target-request-id "listen/query"})
        listen-response
        (protocol/success
         {::protocol/request-id "listen/query"
          ::protocol/database-name "default"
          ::protocol/attachment attachment
          ::protocol/coordinate point
          ::protocol/listening? true})
        unlisten-response
        (protocol/success
         {::protocol/request-id "unlisten/query"
          ::protocol/target-request-id "listen/query"
          ::protocol/listening? false})
        event
        {::protocol/event protocol/datoms-event
         ::protocol/request-id "listen/query"
         ::protocol/coordinate point
         ::protocol/datoms [datom]}
        resynchronization
        {::protocol/event protocol/resynchronization-event
         ::protocol/request-id "listen/query"
         ::protocol/coordinate point}]
    (is (every? protocol/valid-request?
                [query-listen datom-listen unlisten]))
    (is (every? protocol/valid-response?
                [listen-response unlisten-response event resynchronization]))
    (doseq [value [query-listen datom-listen unlisten listen-response
                   unlisten-response event resynchronization]]
      (is (= value (transit-roundtrip value))))
    (is (false? (protocol/valid-request?
                 (assoc query-listen ::protocol/datom-patterns
                        [{:seon.db/a :person/name}]))))
    (is (false? (protocol/valid-request?
                 (assoc unlisten :transport/session-id "private"))))
    (is (false? (protocol/valid-response?
                 (assoc event ::protocol/database-name "default"))))))
