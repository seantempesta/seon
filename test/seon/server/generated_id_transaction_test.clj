(ns seon.server.generated-id-transaction-test
  "Behavioral coverage for atomic generated-id transactions at the sole writer."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.datom :as dh.datom]
            [seon.db.id :as id]
            [seon.server.boot :as boot]
            [seon.server.wire :as wire]))

(def ^:private runtime (boot/writer-runtime))

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
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.schema/key
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.db.id/generator
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])

(def generator-policy-tx
  [{:seon.schema/key :thing/id
    :seon.db.id/generator :seon.db.id.generator/compact}
   {:seon.schema/key :other/id
    :seon.db.id/generator :seon.db.id.generator/compact}
   {:seon.schema/key :child/id
    :seon.db.id/generator :seon.db.id.generator/compact}])

(defn- memory-config
  []
  {:store {:backend :memory
           :id (java.util.UUID/randomUUID)}
   :schema-flexibility :write
   :keep-history? true})

(defn- seed-conn!
  [conn]
  (d/transact conn schema-tx)
  (#'wire/seed-base-schema! conn)
  (d/transact conn generator-policy-tx)
  conn)

(defn- mem-conn
  ([] (mem-conn true))
  ([configured?]
   (let [base-config (memory-config)
         connect-config (if configured?
                          (id/allocation-connect-config base-config)
                          base-config)]
     (d/create-database base-config)
     (seed-conn! (d/connect connect-config)))))

(defn- candidate
  [allocation-key identity-attr value]
  {:seon.db.id/key allocation-key
   :seon.db.id/identity-attr identity-attr
   :seon.db.id/value value})

(defn- compact-fixture
  "A readable deterministic value satisfying the stored compact policy."
  [label]
  (subs (str "x" (str/replace label #"[^a-z0-9]" "0") "00000000000")
        0 12))

(defn- allocate-op
  [conn tx-data candidates]
  (wire/handle-op
   runtime
   conn
   {:seon.store.wire/op "transact"
    :seon.store.wire/id (str (random-uuid))
    :seon.store.wire/tx-data tx-data
    :seon.store.wire/generated-candidates candidates}))

(defn- raw-transact-response
  ([conn tx-data]
   (raw-transact-response conn tx-data nil))
  ([conn tx-data tx-meta]
   (#'wire/handle-req
    runtime
    conn
    (cond-> {:seon.store.wire/op "transact"
             :seon.store.wire/id (str (random-uuid))
             :seon.store.wire/tx-data tx-data}
      (seq tx-meta) (assoc :seon.store.wire/tx-meta tx-meta)))))

(defn- thrown
  [f]
  (try
    (f)
    nil
    (catch Throwable throwable
      throwable)))

(defn- nested-ex-data
  [throwable]
  (let [data-chain (keep ex-data
                         (take-while some? (iterate ex-cause throwable)))]
    (or (some #(when (contains? % ::id/error) %) data-chain)
        (first data-chain))))

(deftest unconfigured-wire-connection-rejects-before-commit
  (let [conn (mem-conn false)
        before (:max-tx (d/db conn))
        value (compact-fixture "must-not-land")
        attempted (candidate :allocation/thing :thing/id value)
        response (#'wire/handle-req
                  runtime
                  conn
                  {:seon.store.wire/op "transact"
                   :seon.store.wire/id "unconfigured-allocation"
                   :seon.store.wire/tx-data
                   [{:thing/id value}]
                   :seon.store.wire/generated-candidates [attempted]})]
    (is (false? (:seon.store.wire/ok response)))
    (is (= "protocol" (:seon.store.wire/error-kind response)))
    (is (= before (:max-tx (d/db conn))))
    (is (empty? (d/datoms (d/db conn) :avet :thing/id value)))))

(deftest empty-generated-manifest-is-a-protocol-error-without-a-commit
  (let [conn (mem-conn)
        value (compact-fixture "must-not-land")
        before (:max-tx (d/db conn))
        response (allocate-op conn [{:thing/id value}]
                              [])]
    (is (false? (:seon.store.wire/ok response)))
    (is (= "protocol" (:seon.store.wire/error-kind response)))
    (is (= before (:max-tx (d/db conn))))
    (is (empty? (d/datoms (d/db conn) :avet :thing/id value)))))

(deftest writer-rejects-unallocated-current-identities-at-every-data-seam
  (testing "an ordinary entity map"
    (let [conn (mem-conn)
          value (compact-fixture "raw-current")
          before (:max-tx (d/db conn))
          response (raw-transact-response conn [{:thing/id value}])]
      (is (false? (:seon.store.wire/ok response)))
      (is (= "datahike" (:seon.store.wire/error-kind response)))
      (is (= before (:max-tx (d/db conn))))
      (is (empty? (d/datoms (d/db conn) :avet :thing/id value)))))

  (testing "a nested component map"
    (let [conn (mem-conn)
          value (compact-fixture "nested-raw")
          before (:max-tx (d/db conn))
          response (raw-transact-response
                    conn
                    [{:parent/id "parent-for-rejection"
                      :parent/child {:child/id value
                                     :child/name "must-not-land"}}])]
      (is (false? (:seon.store.wire/ok response)))
      (is (= before (:max-tx (d/db conn))))
      (is (empty? (d/datoms (d/db conn) :avet :child/id value)))
      (is (empty? (d/datoms (d/db conn) :avet :child/name
                            "must-not-land")))))

  (testing "transaction metadata"
    (let [conn (mem-conn)
          value (compact-fixture "tx-meta-raw")
          before (:max-tx (d/db conn))
          response (raw-transact-response
                    conn
                    [{:thing/name "tx-meta-must-not-land"}]
                    {:thing/id value})]
      (is (false? (:seon.store.wire/ok response)))
      (is (= before (:max-tx (d/db conn))))
      (is (empty? (d/datoms (d/db conn) :avet :thing/id value)))
      (is (empty? (d/datoms (d/db conn) :avet :thing/name
                            "tx-meta-must-not-land"))))))

(deftest writer-validates-transaction-function-output
  (let [conn (mem-conn)
        value (compact-fixture "tx-function")
        before (:max-tx (d/db conn))
        error (thrown
               #(d/transact
                 conn
                 {:tx-data
                  [[:db.fn/call
                    (fn [_db] [{:thing/id value
                                :thing/name "tx-fn-must-not-land"}])]]}))
        data (nested-ex-data error)]
    (is (some? error))
    (is (= :seon.db.id.error/unallocated-generated-identity
           (::id/error data)))
    (is (= before (:max-tx (d/db conn))))
    (is (empty? (d/datoms (d/db conn) :avet :thing/id value)))
    (is (empty? (d/datoms (d/db conn) :avet :thing/name
                          "tx-fn-must-not-land")))))

(deftest exact-current-identity-reassertion-keeps-normal-upsert-semantics
  (let [conn (mem-conn)
        value (compact-fixture "exact-reassert")
        seed (allocate-op
              conn [{:thing/id value}]
              [(candidate :setup/thing :thing/id value)])
        eid (get-in seed [:seon.store.wire/generated-eids :setup/thing])
        response (raw-transact-response
                  conn [{:thing/id value :thing/name "After"}])
        stored (d/pull (d/db conn) '[*] [:thing/id value])]
    (is (true? (:seon.store.wire/ok seed)))
    (is (true? (:seon.store.wire/ok response)))
    (is (= eid (:db/id stored)))
    (is (= "After" (:thing/name stored)))
    (is (= 1 (count (d/datoms (d/db conn) :avet :thing/id value))))))

(deftest writer-rejects-cross-attribute-policy-collisions
  (let [conn (mem-conn)
        legacy-value "crosslegacy001"
        _ (d/transact conn [{:thing/id legacy-value}])
        before (:max-tx (d/db conn))
        error (thrown #(d/transact conn [{:other/id legacy-value}]))
        data (nested-ex-data error)]
    (is (= :seon.db.id.error/cross-attribute-identity-collision
           (::id/error data)))
    (is (= before (:max-tx (d/db conn))))
    (is (empty? (d/datoms (d/db conn) :avet :other/id legacy-value)))
    (is (= 1 (count (d/datoms (d/db conn) :avet :thing/id legacy-value))))))

(deftest writer-audits-generator-policy-removal-and-change
  (testing "a policy with existing values cannot be removed"
    (let [conn (mem-conn)
          legacy-value "removalvalue01"
          _ (d/transact conn [{:thing/id legacy-value}])
          before (:max-tx (d/db conn))
          error (thrown
                 #(d/transact
                   conn
                   [[:db/retract
                     [:seon.schema/key :thing/id]
                     :seon.db.id/generator
                     :seon.db.id.generator/compact]]))
          data (nested-ex-data error)]
      (is (= :seon.db.id.error/generator-policy-removal-in-use
             (::id/error data)))
      (is (= before (:max-tx (d/db conn))))
      (is (= :seon.db.id.generator/compact
             (:seon.db.id/generator
              (d/entity (d/db conn) [:seon.schema/key :thing/id]))))))

  (testing "an invalid policy change cannot replace the stored fact"
    (let [conn (mem-conn)
          before (:max-tx (d/db conn))
          error (thrown
                 #(d/transact
                   conn
                   [{:seon.schema/key :thing/id
                     :seon.db.id/generator
                     :seon.db.id.generator/human-readable}]))
          data (nested-ex-data error)]
      (is (= :seon.db.id.error/human-readable-non-agent
             (::id/error data)))
      (is (= before (:max-tx (d/db conn))))
      (is (= :seon.db.id.generator/compact
             (:seon.db.id/generator
              (d/entity (d/db conn) [:seon.schema/key :thing/id])))))))

(deftest generator-policy-facts-survive-a-cold-connection-reopen
  (let [config (memory-config)
        connect-config (id/allocation-connect-config config)]
    (d/create-database config)
    (let [first-conn (seed-conn! (d/connect connect-config))
          before (id/generator-policies {::id/db-value (d/db first-conn)})]
      (d/release first-conn)
      (let [reopened (d/connect connect-config)
            after (id/generator-policies {::id/db-value (d/db reopened)})
            value (compact-fixture "cold-reopen")
            response (allocate-op
                      reopened [{:thing/id value}]
                      [(candidate :allocation/reopened :thing/id value)])]
        (is (= before after))
        (is (= :seon.db.id.generator/compact (get after :thing/id)))
        (is (true? (:seon.store.wire/ok response)))
        (is (pos-int?
             (get-in response
                     [:seon.store.wire/generated-eids
                      :allocation/reopened])))
        (d/release reopened)))))

(deftest multi-id-allocation-commits-relationships-and-returns-eids
  (let [conn (mem-conn)
        thing-value (compact-fixture "fresh-thing")
        other-value (compact-fixture "fresh-other")
        thing (candidate :allocation/thing :thing/id thing-value)
        other (candidate :allocation/other :other/id other-value)
        response (allocate-op
                  conn
                  [{:db/id "thing-temp"
                    :thing/id thing-value
                    :thing/name "Thing"
                    :thing/other "other-temp"}
                   {:db/id "other-temp"
                    :other/id other-value}]
                  [thing other])
        eids (:seon.store.wire/generated-eids response)
        thing-eid (:allocation/thing eids)
        other-eid (:allocation/other eids)
        stored (d/pull (d/db conn) '[*] [:thing/id thing-value])]
    (is (true? (:seon.store.wire/ok response)))
    (is (and (pos-int? thing-eid) (pos-int? other-eid)))
    (is (not= thing-eid other-eid))
    (is (= other-eid (get-in stored [:thing/other :db/id]))
        "a schema-declared ref to an old string tempid is rewritten")
    (is (= {"thing-temp" thing-eid "other-temp" other-eid}
           (select-keys (:seon.store.wire/tempids response)
                        ["thing-temp" "other-temp"])))
    (is (= other-eid (:db/id (d/pull (d/db conn) '[*]
                                     [:other/id other-value]))))))

(deftest interleaved-automatic-entities-cannot-collide-with-candidates
  (let [conn (mem-conn)
        thing-value (compact-fixture "first-candidate")
        other-value (compact-fixture "later-candidate")
        thing (candidate :allocation/thing :thing/id thing-value)
        other (candidate :allocation/other :other/id other-value)
        response (allocate-op conn
                              [{:thing/id thing-value}
                               {:thing/name "automatic-between"}
                               {:other/id other-value}]
                              [thing other])
        thing-eid (get-in response
                          [:seon.store.wire/generated-eids :allocation/thing])
        other-eid (get-in response
                          [:seon.store.wire/generated-eids :allocation/other])
        automatic-eid (:e (first (d/datoms (d/db conn) :avet :thing/name
                                           "automatic-between")))]
    (is (true? (:seon.store.wire/ok response)))
    (is (= 3 (count (set [thing-eid automatic-eid other-eid]))))
    (is (= thing-eid (:db/id (d/pull (d/db conn) '[*]
                                     [:thing/id thing-value]))))
    (is (= other-eid (:db/id (d/pull (d/db conn) '[*]
                                     [:other/id other-value]))))))

(deftest generated-identity-can-be-a-nested-component
  (let [conn (mem-conn)
        child-value (compact-fixture "nested-child")
        child (candidate :allocation/child :child/id child-value)
        response (allocate-op
                  conn
                  [{:parent/id "known-parent"
                    :parent/child {:child/id child-value
                                   :child/name "Nested"}}]
                  [child])
        child-eid (get-in response
                          [:seon.store.wire/generated-eids :allocation/child])
        parent (d/pull (d/db conn) '[*] [:parent/id "known-parent"])]
    (is (true? (:seon.store.wire/ok response)))
    (is (pos-int? child-eid))
    (is (= child-eid (get-in parent [:parent/child :db/id])))
    (is (= "Nested" (:child/name (d/pull (d/db conn) '[*] child-eid))))))

(deftest allocation-preserves-noncandidate-entity-semantics
  (let [conn (mem-conn)
        existing-value "knownother0000"
        candidate-value (compact-fixture "reserved-candidate")
        existing-report (d/transact conn [{:other/id existing-value
                                           :other/name "Before"}])
        existing-eid (:e (first (d/datoms (:db-after existing-report)
                                          :avet :other/id existing-value)))
        attempted (candidate :allocation/thing :thing/id candidate-value)
        response (allocate-op
                  conn
                  [{:thing/name "anonymous-before-candidate"}
                   {:other/id existing-value :other/name "After"}
                   {:thing/id candidate-value
                    :thing/others [:other/id existing-value]}]
                  [attempted])
        generated-eid (get-in response
                              [:seon.store.wire/generated-eids
                               :allocation/thing])
        anonymous-eid (:e (first (d/datoms (d/db conn) :avet :thing/name
                                           "anonymous-before-candidate")))
        stored-other (d/pull (d/db conn) '[*] [:other/id existing-value])
        stored-thing (d/pull (d/db conn) '[*]
                             [:thing/id candidate-value])]
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
          value (compact-fixture "already-used")
          seed (allocate-op
                conn [{:thing/id value}]
                [(candidate :setup/thing :thing/id value)])
          before (:max-tx (d/db conn))
          attempted (candidate :allocation/thing :thing/id value)
          response (allocate-op conn
                                [{:thing/id value
                                  :thing/name "must-not-land"}]
                                [attempted])]
      (is (true? (:seon.store.wire/ok seed)))
      (is (false? (:seon.store.wire/ok response)))
      (is (= "generated-candidate-conflict"
             (:seon.store.wire/error-kind response)))
      (is (= attempted (:seon.store.wire/generated-candidate response)))
      (is (= before (:max-tx (d/db conn))))
      (is (empty? (d/datoms (d/db conn) :avet :thing/name "must-not-land")))))

  (testing "a different generated identity attr"
    (let [conn (mem-conn)
          value (compact-fixture "cross-attr")
          seed (allocate-op
                conn [{:other/id value}]
                [(candidate :setup/other :other/id value)])
          before (:max-tx (d/db conn))
          attempted (candidate :allocation/thing :thing/id value)
          response (allocate-op conn
                                [{:thing/id value
                                  :thing/name "must-not-land"}]
                                [attempted])]
      (is (true? (:seon.store.wire/ok seed)))
      (is (= "generated-candidate-conflict"
             (:seon.store.wire/error-kind response)))
      (is (= attempted (:seon.store.wire/generated-candidate response)))
      (is (= before (:max-tx (d/db conn))))
      (is (empty? (d/datoms (d/db conn) :avet :thing/name "must-not-land")))))

  (testing "the incoming tx cannot reuse a candidate under another managed attr"
    (let [conn (mem-conn)
          value (compact-fixture "same-tx-cross")
          attempted (candidate :allocation/thing :thing/id value)
          before (:max-tx (d/db conn))
          response (allocate-op conn
                                [{:thing/id value}
                                 {:other/id value}]
                                [attempted])]
      (is (= "generated-candidate-conflict"
             (:seon.store.wire/error-kind response)))
      (is (= attempted (:seon.store.wire/generated-candidate response)))
      (is (= before (:max-tx (d/db conn))))
      (is (empty? (d/datoms (d/db conn) :avet :thing/id value)))
      (is (empty? (d/datoms (d/db conn) :avet :other/id value))))))

(deftest duplicate-candidates-and-ambiguous-entities-are-rejected-atomically
  (testing "one request cannot allocate the same candidate twice"
    (let [conn (mem-conn)
          value (compact-fixture "duplicate")
          first-candidate (candidate :allocation/thing :thing/id value)
          second-candidate (candidate :allocation/other :other/id value)
          before (:max-tx (d/db conn))
          response (allocate-op conn
                                [{:thing/id value}
                                 {:other/id value}]
                                [first-candidate second-candidate])]
      (is (= "generated-candidate-conflict"
             (:seon.store.wire/error-kind response)))
      (is (= second-candidate
             (:seon.store.wire/generated-candidate response)))
      (is (= before (:max-tx (d/db conn))))))

  (testing "a manifest must identify exactly one entity map"
    (let [conn (mem-conn)
          value (compact-fixture "ambiguous")
          attempted (candidate :allocation/thing :thing/id value)
          before (:max-tx (d/db conn))
          response (allocate-op conn
                                [{:thing/id value}
                                 {:thing/id value}]
                                [attempted])]
      (is (= "protocol" (:seon.store.wire/error-kind response)))
      (is (= before (:max-tx (d/db conn))))
      (is (empty? (d/datoms (d/db conn) :avet :thing/id value)))))

  (testing "a missing candidate entity is a protocol error"
    (let [conn (mem-conn)
          attempted (candidate :allocation/thing :thing/id
                               (compact-fixture "missing"))
          before (:max-tx (d/db conn))
          response (allocate-op conn
                                [{:thing/name "unrelated"}]
                                [attempted])]
      (is (= "protocol" (:seon.store.wire/error-kind response)))
      (is (= before (:max-tx (d/db conn))))))

  (testing "a candidate cannot target a caller-selected concrete eid"
    (let [conn (mem-conn)
          value (compact-fixture "not-new")
          attempted (candidate :allocation/thing :thing/id value)
          before (:max-tx (d/db conn))
          response (allocate-op conn
                                [{:db/id 100 :thing/id value}]
                                [attempted])]
      (is (= "protocol" (:seon.store.wire/error-kind response)))
      (is (= before (:max-tx (d/db conn))))))

  (testing "another existing identity cannot upsert a candidate entity"
    (let [conn (mem-conn)
          _ (d/transact conn [{:external/id "existing-identity"}])
          value (compact-fixture "must-stay-new")
          attempted (candidate :allocation/thing :thing/id value)
          before (:max-tx (d/db conn))
          response (allocate-op conn
                                [{:thing/id value
                                  :external/id "existing-identity"}]
                                [attempted])]
      (is (= "protocol" (:seon.store.wire/error-kind response)))
      (is (= before (:max-tx (d/db conn))))
      (is (empty? (d/datoms (d/db conn) :avet :thing/id value))))))

(deftest unrelated-uniqueness-errors-remain-ordinary-datahike-errors
  (let [conn (mem-conn)
        before (:max-tx (d/db conn))
        value (compact-fixture "fresh-candidate")
        attempted (candidate :allocation/thing :thing/id value)
        response (#'wire/handle-req
                  runtime
                  conn
                  {:seon.store.wire/op "transact"
                   :seon.store.wire/id "unrelated-unique-conflict"
                   :seon.store.wire/tx-data [{:thing/id value
                                              :thing/name "must-not-land"}
                                             {:external/code "taken-twice"}
                                             {:external/code "taken-twice"}]
                   :seon.store.wire/generated-candidates [attempted]})]
    (is (false? (:seon.store.wire/ok response)))
    (is (not= "generated-candidate-conflict"
              (:seon.store.wire/error-kind response)))
    (is (= before (:max-tx (d/db conn))))
    (is (empty? (d/datoms (d/db conn) :avet :thing/id value)))
    (is (empty? (d/datoms (d/db conn) :avet :thing/name "must-not-land")))))

(deftest exact-datahike-candidate-errors-are-normalized
  (let [value (compact-fixture "raced-candidate")
        attempted (candidate :allocation/thing :thing/id value)
        request {:seon.store.wire/op "transact"
                 :seon.store.wire/id "exact-candidate-conflict"
                 :seon.store.wire/tx-data [{:thing/id value}]
                 :seon.store.wire/generated-candidates [attempted]}
        wrapped (fn [cause]
                  (ex-info "writer wrapper"
                           {}
                           (java.util.concurrent.ExecutionException. cause)))]
    (testing "Datahike map-form upsert conflicts"
      (let [conn (mem-conn)
            failure (wrapped
                     (ex-info "upsert conflict"
                              {:error :transact/upsert
                               :assertion [1 :thing/id value]}))
            response (with-redefs [d/transact (fn [& _] (throw failure))]
                       (wire/handle-op runtime conn request))]
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
                                       1 :thing/id value 2)}))
            response (with-redefs [d/transact (fn [& _] (throw failure))]
                       (wire/handle-op runtime conn request))]
        (is (= "generated-candidate-conflict"
               (:seon.store.wire/error-kind response)))
        (is (= attempted (:seon.store.wire/generated-candidate response)))))))

(deftest concurrent-attempts-have-one-winner
  (let [conn (mem-conn)
        value (compact-fixture "contended")
        attempted (candidate :allocation/thing :thing/id value)
        request (fn [label]
                  (allocate-op conn
                               [{:thing/id value :thing/name label}]
                               [attempted]))
        responses (mapv deref [(future (request "one"))
                               (future (request "two"))])]
    (is (= 1 (count (filter :seon.store.wire/ok responses))))
    (is (= 1 (count (filter #(= "generated-candidate-conflict"
                                (:seon.store.wire/error-kind %))
                            responses))))
    (is (= 1 (count (d/datoms (d/db conn) :avet :thing/id value))))))

(deftest generated-request-fingerprint-is-candidate-only-protocol-v2
  (let [conn (mem-conn)
        value (compact-fixture "fingerprint")
        wire-id "generated-fingerprint-v2"
        candidate-a (candidate :allocation/thing :thing/id value)
        candidate-b (assoc candidate-a :seon.db.id/key :allocation/renamed)
        request (fn [manifest]
                  {:seon.store.wire/op "transact"
                   :seon.store.wire/id wire-id
                   :seon.store.wire/tx-data [{:thing/id value}]
                   :seon.store.wire/generated-candidates manifest})
        first-response (wire/handle-op runtime conn (request [candidate-a]))
        recovered-response (wire/handle-op runtime conn (request [candidate-a]))
        reused-response (wire/handle-op runtime conn (request [candidate-b]))
        tx-entity (d/entity (d/db conn) [:seon.store.wire/id wire-id])]
    (is (true? (:seon.store.wire/ok first-response)))
    (is (true? (:seon.store.wire/recovered? recovered-response))
        "the same candidate-only request recovers the original commit")
    (is (= "wire-id-conflict" (:seon.store.wire/error-kind reused-response))
        "changing the candidate manifest changes the durable fingerprint")
    (is (= 2 (:seon.store.wire/protocol-version tx-entity))
        "the candidate-only fingerprint is recorded as wire protocol v2")))

(deftest manifest-less-transact-keeps-the-original-wire-contract
  (let [conn (mem-conn)
        response (wire/handle-op
                  runtime
                  conn
                  {:seon.store.wire/op "transact"
                   :seon.store.wire/id "manifestless-contract"
                   :seon.store.wire/tx-data [{:db/id "legacy-temp"
                                              :thing/id "legacy00000000"}]})]
    (is (true? (:seon.store.wire/ok response)))
    (is (pos-int? (get-in response
                          [:seon.store.wire/tempids "legacy-temp"])))
    (is (not (contains? response :seon.store.wire/generated-eids)))
    (is (= #{:seon.store.wire/ok
             :seon.store.wire/id
             :seon.store.wire/basis-t
             :seon.store.wire/basis-t-before
             :seon.store.wire/tempids
             :seon.store.wire/tx-data
             :seon.store.wire/tx-meta
             :seon.store.wire/datoms-added
             :seon.store.wire/datoms-retracted}
           (set (keys response))))))
