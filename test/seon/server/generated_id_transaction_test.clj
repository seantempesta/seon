(ns seon.server.generated-id-transaction-test
  "Behavioral coverage for atomic generated-id transactions at the sole writer."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.datom :as dh.datom]
            [seon.db.id :as id]
            [seon.server.wire :as wire]))

(def schema-tx
  [{:db/ident :thing/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :thing/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :thing/other
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :thing/others
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident :other/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :other/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :external/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :external/code
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/value}
   {:db/ident :parent/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :parent/child
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/isComponent true}
   {:db/ident :child/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :child/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(defn- mem-conn
  ([] (mem-conn true))
  ([configured?]
   (let [base-config {:store {:backend :memory
                              :id (java.util.UUID/randomUUID)}
                      :schema-flexibility :write
                      :keep-history? true}
         connect-config (if configured?
                          (id/allocation-connect-config base-config)
                          base-config)]
     (d/create-database base-config)
     (let [conn (d/connect connect-config)]
       (d/transact conn schema-tx)
       conn))))

(defn- candidate
  [allocation-key identity-attr value]
  {:seon.db.id/key allocation-key
   :seon.db.id/identity-attr identity-attr
   :seon.db.id/value value})

(defn- allocate-op
  [conn tx-data candidates generated-attrs]
  (wire/handle-op
   conn
   {:seon.store.wire/op "transact"
    :seon.store.wire/tx-data tx-data
    :seon.store.wire/generated-candidates candidates
    :seon.store.wire/generated-identity-attrs generated-attrs}))

(deftest unconfigured-wire-connection-rejects-before-commit
  (let [conn (mem-conn false)
        before (:max-tx (d/db conn))
        attempted (candidate :allocation/thing :thing/id "must-not-land")
        response (#'wire/handle-req
                  conn
                  {:seon.store.wire/op "transact"
                   :seon.store.wire/tx-data
                   [{:thing/id "must-not-land"}]
                   :seon.store.wire/generated-candidates [attempted]
                   :seon.store.wire/generated-identity-attrs [:thing/id]})]
    (is (false? (:seon.store.wire/ok response)))
    (is (= "datahike" (:seon.store.wire/error-kind response)))
    (is (= before (:max-tx (d/db conn))))
    (is (empty? (d/datoms (d/db conn) :avet :thing/id "must-not-land")))))

(deftest empty-generated-manifest-is-a-protocol-error-without-a-commit
  (let [conn (mem-conn)
        before (:max-tx (d/db conn))
        response (allocate-op conn [{:thing/id "must-not-land"}]
                              [] [:thing/id])]
    (is (false? (:seon.store.wire/ok response)))
    (is (= "protocol" (:seon.store.wire/error-kind response)))
    (is (= before (:max-tx (d/db conn))))
    (is (empty? (d/datoms (d/db conn) :avet :thing/id "must-not-land")))))

(deftest multi-id-allocation-commits-relationships-and-returns-eids
  (let [conn (mem-conn)
        thing (candidate :allocation/thing :thing/id "fresh-thing")
        other (candidate :allocation/other :other/id "fresh-other")
        response (allocate-op
                  conn
                  [{:db/id "thing-temp"
                    :thing/id "fresh-thing"
                    :thing/name "Thing"
                    :thing/other "other-temp"}
                   {:db/id "other-temp"
                    :other/id "fresh-other"}]
                  [thing other]
                  [:thing/id :other/id])
        eids (:seon.store.wire/generated-eids response)
        thing-eid (:allocation/thing eids)
        other-eid (:allocation/other eids)
        stored (d/pull (d/db conn) '[*] [:thing/id "fresh-thing"])]
    (is (true? (:seon.store.wire/ok response)))
    (is (and (pos-int? thing-eid) (pos-int? other-eid)))
    (is (not= thing-eid other-eid))
    (is (= other-eid (get-in stored [:thing/other :db/id]))
        "a schema-declared ref to an old string tempid is rewritten")
    (is (= {"thing-temp" thing-eid "other-temp" other-eid}
           (select-keys (:seon.store.wire/tempids response)
                        ["thing-temp" "other-temp"])))
    (is (= other-eid (:db/id (d/pull (d/db conn) '[*]
                                     [:other/id "fresh-other"]))))))

(deftest interleaved-automatic-entities-cannot-collide-with-candidates
  (let [conn (mem-conn)
        thing (candidate :allocation/thing :thing/id "first-candidate")
        other (candidate :allocation/other :other/id "later-candidate")
        response (allocate-op conn
                              [{:thing/id "first-candidate"}
                               {:thing/name "automatic-between"}
                               {:other/id "later-candidate"}]
                              [thing other]
                              [:thing/id :other/id])
        thing-eid (get-in response
                          [:seon.store.wire/generated-eids :allocation/thing])
        other-eid (get-in response
                          [:seon.store.wire/generated-eids :allocation/other])
        automatic-eid (:e (first (d/datoms (d/db conn) :avet :thing/name
                                           "automatic-between")))]
    (is (true? (:seon.store.wire/ok response)))
    (is (= 3 (count (set [thing-eid automatic-eid other-eid]))))
    (is (= thing-eid (:db/id (d/pull (d/db conn) '[*]
                                     [:thing/id "first-candidate"]))))
    (is (= other-eid (:db/id (d/pull (d/db conn) '[*]
                                     [:other/id "later-candidate"]))))))

(deftest generated-identity-can-be-a-nested-component
  (let [conn (mem-conn)
        child (candidate :allocation/child :child/id "nested-child")
        response (allocate-op
                  conn
                  [{:parent/id "known-parent"
                    :parent/child {:child/id "nested-child"
                                   :child/name "Nested"}}]
                  [child]
                  [:child/id])
        child-eid (get-in response
                          [:seon.store.wire/generated-eids :allocation/child])
        parent (d/pull (d/db conn) '[*] [:parent/id "known-parent"])]
    (is (true? (:seon.store.wire/ok response)))
    (is (pos-int? child-eid))
    (is (= child-eid (get-in parent [:parent/child :db/id])))
    (is (= "Nested" (:child/name (d/pull (d/db conn) '[*] child-eid))))))

(deftest allocation-preserves-noncandidate-entity-semantics
  (let [conn (mem-conn)
        existing-report (d/transact conn [{:other/id "known-other"
                                           :other/name "Before"}])
        existing-eid (:e (first (d/datoms (:db-after existing-report)
                                          :avet :other/id "known-other")))
        attempted (candidate :allocation/thing :thing/id "reserved-candidate")
        response (allocate-op
                  conn
                  [{:thing/name "anonymous-before-candidate"}
                   {:other/id "known-other" :other/name "After"}
                   {:thing/id "reserved-candidate"
                    :thing/others [:other/id "known-other"]}]
                  [attempted]
                  [:thing/id])
        generated-eid (get-in response
                              [:seon.store.wire/generated-eids
                               :allocation/thing])
        anonymous-eid (:e (first (d/datoms (d/db conn) :avet :thing/name
                                           "anonymous-before-candidate")))
        stored-other (d/pull (d/db conn) '[*] [:other/id "known-other"])
        stored-thing (d/pull (d/db conn) '[*]
                             [:thing/id "reserved-candidate"])]
    (is (true? (:seon.store.wire/ok response)))
    (is (not= anonymous-eid generated-eid)
        "an earlier anonymous map cannot consume the reserved candidate eid")
    (is (= existing-eid (:db/id stored-other))
        "a noncandidate identity map retains Datahike's normal upsert")
    (is (= "After" (:other/name stored-other)))
    (is (= #{existing-eid}
           (into #{} (map :db/id) (:thing/others stored-thing)))
        "a cardinality-many single lookup ref is not mistaken for tempids")))

(deftest existing-generated-values-conflict-without-a-commit
  (testing "the same generated attr"
    (let [conn (mem-conn)
          _ (d/transact conn [{:thing/id "already-used"}])
          before (:max-tx (d/db conn))
          attempted (candidate :allocation/thing :thing/id "already-used")
          response (allocate-op conn
                                [{:thing/id "already-used"
                                  :thing/name "must-not-land"}]
                                [attempted]
                                [:thing/id :other/id])]
      (is (false? (:seon.store.wire/ok response)))
      (is (= "generated-candidate-conflict"
             (:seon.store.wire/error-kind response)))
      (is (= attempted (:seon.store.wire/generated-candidate response)))
      (is (= before (:max-tx (d/db conn))))
      (is (empty? (d/datoms (d/db conn) :avet :thing/name "must-not-land")))))

  (testing "a different generated identity attr"
    (let [conn (mem-conn)
          _ (d/transact conn [{:other/id "cross-attr"}])
          before (:max-tx (d/db conn))
          attempted (candidate :allocation/thing :thing/id "cross-attr")
          response (allocate-op conn
                                [{:thing/id "cross-attr"
                                  :thing/name "must-not-land"}]
                                [attempted]
                                [:thing/id :other/id])]
      (is (= "generated-candidate-conflict"
             (:seon.store.wire/error-kind response)))
      (is (= attempted (:seon.store.wire/generated-candidate response)))
      (is (= before (:max-tx (d/db conn))))
      (is (empty? (d/datoms (d/db conn) :avet :thing/name "must-not-land")))))

  (testing "the incoming tx cannot reuse a candidate under another managed attr"
    (let [conn (mem-conn)
          attempted (candidate :allocation/thing :thing/id "same-tx-cross")
          before (:max-tx (d/db conn))
          response (allocate-op conn
                                [{:thing/id "same-tx-cross"}
                                 {:other/id "same-tx-cross"}]
                                [attempted]
                                [:thing/id :other/id])]
      (is (= "generated-candidate-conflict"
             (:seon.store.wire/error-kind response)))
      (is (= attempted (:seon.store.wire/generated-candidate response)))
      (is (= before (:max-tx (d/db conn))))
      (is (empty? (d/datoms (d/db conn) :avet :thing/id "same-tx-cross")))
      (is (empty? (d/datoms (d/db conn) :avet :other/id "same-tx-cross"))))))

(deftest duplicate-candidates-and-ambiguous-entities-are-rejected-atomically
  (testing "one request cannot allocate the same candidate twice"
    (let [conn (mem-conn)
          first-candidate (candidate :allocation/thing :thing/id "duplicate")
          second-candidate (candidate :allocation/other :other/id "duplicate")
          before (:max-tx (d/db conn))
          response (allocate-op conn
                                [{:thing/id "duplicate"}
                                 {:other/id "duplicate"}]
                                [first-candidate second-candidate]
                                [:thing/id :other/id])]
      (is (= "generated-candidate-conflict"
             (:seon.store.wire/error-kind response)))
      (is (= second-candidate
             (:seon.store.wire/generated-candidate response)))
      (is (= before (:max-tx (d/db conn))))))

  (testing "a manifest must identify exactly one entity map"
    (let [conn (mem-conn)
          attempted (candidate :allocation/thing :thing/id "ambiguous")
          before (:max-tx (d/db conn))
          response (allocate-op conn
                                [{:thing/id "ambiguous"}
                                 {:thing/id "ambiguous"}]
                                [attempted]
                                [:thing/id])]
      (is (= "protocol" (:seon.store.wire/error-kind response)))
      (is (= before (:max-tx (d/db conn))))
      (is (empty? (d/datoms (d/db conn) :avet :thing/id "ambiguous")))))

  (testing "a missing candidate entity is a protocol error"
    (let [conn (mem-conn)
          attempted (candidate :allocation/thing :thing/id "missing")
          before (:max-tx (d/db conn))
          response (allocate-op conn
                                [{:thing/name "unrelated"}]
                                [attempted]
                                [:thing/id])]
      (is (= "protocol" (:seon.store.wire/error-kind response)))
      (is (= before (:max-tx (d/db conn))))))

  (testing "a candidate cannot target a caller-selected concrete eid"
    (let [conn (mem-conn)
          attempted (candidate :allocation/thing :thing/id "not-new")
          before (:max-tx (d/db conn))
          response (allocate-op conn
                                [{:db/id 100 :thing/id "not-new"}]
                                [attempted]
                                [:thing/id])]
      (is (= "protocol" (:seon.store.wire/error-kind response)))
      (is (= before (:max-tx (d/db conn))))))

  (testing "another existing identity cannot upsert a candidate entity"
    (let [conn (mem-conn)
          _ (d/transact conn [{:external/id "existing-identity"}])
          attempted (candidate :allocation/thing :thing/id "must-stay-new")
          before (:max-tx (d/db conn))
          response (allocate-op conn
                                [{:thing/id "must-stay-new"
                                  :external/id "existing-identity"}]
                                [attempted]
                                [:thing/id])]
      (is (= "protocol" (:seon.store.wire/error-kind response)))
      (is (= before (:max-tx (d/db conn))))
      (is (empty? (d/datoms (d/db conn) :avet :thing/id "must-stay-new"))))))

(deftest unrelated-uniqueness-errors-remain-ordinary-datahike-errors
  (let [conn (mem-conn)
        before (:max-tx (d/db conn))
        attempted (candidate :allocation/thing :thing/id "fresh-candidate")
        response (#'wire/handle-req
                  conn
                  {:seon.store.wire/op "transact"
                   :seon.store.wire/tx-data [{:thing/id "fresh-candidate"
                                              :thing/name "must-not-land"}
                                             {:external/code "taken-twice"}
                                             {:external/code "taken-twice"}]
                   :seon.store.wire/generated-candidates [attempted]
                   :seon.store.wire/generated-identity-attrs [:thing/id]})]
    (is (false? (:seon.store.wire/ok response)))
    (is (not= "generated-candidate-conflict"
              (:seon.store.wire/error-kind response)))
    (is (= before (:max-tx (d/db conn))))
    (is (empty? (d/datoms (d/db conn) :avet :thing/id "fresh-candidate")))
    (is (empty? (d/datoms (d/db conn) :avet :thing/name "must-not-land")))))

(deftest exact-datahike-candidate-errors-are-normalized
  (let [attempted (candidate :allocation/thing :thing/id "raced-candidate")
        request {:seon.store.wire/op "transact"
                 :seon.store.wire/tx-data [{:thing/id "raced-candidate"}]
                 :seon.store.wire/generated-candidates [attempted]
                 :seon.store.wire/generated-identity-attrs [:thing/id]}
        wrapped (fn [cause]
                  (ex-info "writer wrapper"
                           {}
                           (java.util.concurrent.ExecutionException. cause)))]
    (testing "Datahike map-form upsert conflicts"
      (let [conn (mem-conn)
            failure (wrapped
                     (ex-info "upsert conflict"
                              {:error :transact/upsert
                               :assertion [1 :thing/id "raced-candidate"]}))
            response (with-redefs [d/transact (fn [& _] (throw failure))]
                       (wire/handle-op conn request))]
        (is (= "generated-candidate-conflict"
               (:seon.store.wire/error-kind response)))
        (is (= attempted (:seon.store.wire/generated-candidate response)))))

    (testing "Datahike datom-form unique conflicts"
      (let [conn (mem-conn)
            failure (wrapped
                     (ex-info "unique conflict"
                              {:error :transact/unique
                               :attribute :thing/id
                               :datom (dh.datom/datom
                                       1 :thing/id "raced-candidate" 2)}))
            response (with-redefs [d/transact (fn [& _] (throw failure))]
                       (wire/handle-op conn request))]
        (is (= "generated-candidate-conflict"
               (:seon.store.wire/error-kind response)))
        (is (= attempted (:seon.store.wire/generated-candidate response)))))))

(deftest concurrent-attempts-have-one-winner
  (let [conn (mem-conn)
        attempted (candidate :allocation/thing :thing/id "contended")
        request (fn [label]
                  (allocate-op conn
                               [{:thing/id "contended" :thing/name label}]
                               [attempted]
                               [:thing/id]))
        responses (mapv deref [(future (request "one"))
                               (future (request "two"))])]
    (is (= 1 (count (filter :seon.store.wire/ok responses))))
    (is (= 1 (count (filter #(= "generated-candidate-conflict"
                                (:seon.store.wire/error-kind %))
                            responses))))
    (is (= 1 (count (d/datoms (d/db conn) :avet :thing/id "contended"))))))

(deftest manifest-less-transact-keeps-the-original-wire-contract
  (let [conn (mem-conn)
        response (wire/handle-op
                  conn
                  {:seon.store.wire/op "transact"
                   :seon.store.wire/tx-data [{:db/id "legacy-temp"
                                              :thing/id "legacy"}]})]
    (is (true? (:seon.store.wire/ok response)))
    (is (pos-int? (get-in response
                          [:seon.store.wire/tempids "legacy-temp"])))
    (is (not (contains? response :seon.store.wire/generated-eids)))
    (is (= #{:seon.store.wire/ok
             :seon.store.wire/basis-t
             :seon.store.wire/basis-t-before
             :seon.store.wire/tempids
             :seon.store.wire/tx-data
             :seon.store.wire/tx-meta
             :seon.store.wire/datoms-added
             :seon.store.wire/datoms-retracted}
           (set (keys response))))))
