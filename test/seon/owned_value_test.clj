(ns seon.owned-value-test
  (:require [malli.core] [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.db.interface :as dbi]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.eval :as evaluation]
            [seon.test.accretion :as accretion]
            [seon.test-support :as support]))

(defn- with-owned-tree [f]
  (let [forms (assoc (schema.edn/packaged-forms)
                     ::id [:string {:seon.db/identity true}]
                     ::value :int
                     ::extra :string
                     ::children [:vector {:seon.db/component true
                                          :seon.db/component-schema ::child} :seon.db/ref]
                     ::root [:map {:seon.db/attributes true :seon.program/partition :seon.data}
                             [::id ::id] [::children {:optional true} ::children]]
                     ::child [:map {:seon.db/attributes true}
                              [::value ::value] [::extra {:optional true} ::extra] [::children {:optional true} ::children]]
                     ::key [:string {:seon.db/identity true}]
                     ::keyed [:map {:seon.db/attributes true :seon.program/partition :seon.data}
                              [::key ::key] [::value ::value] [::children {:optional true} ::children]])
        projection (schema/build-projection forms)]
    (support/with-database
     {::support/extra-schema
      (schema.datahike/malli->datahike-schema-in projection [::id ::key ::value ::extra ::children])}
     (fn [connection]
       ;; The writer derives its projection from the value's declaration rows
       ;; (9b8c5b405), so the test's own declarations are rows, not a handed
       ;; projection.
       (support/transacted!
        connection
        (mapv (partial accretion/schema-row forms)
              (schema/canonical-schema-rows
               projection (into {} (filter #(= (namespace ::id) (namespace (key %)))) forms))))
       (db/carry-connection-projection-state!
        connection (evaluation/projection-state @connection projection))
       (f connection)))))

(defn- refuses-without-change {:malli/schema [:=> [:cat :seon.db/connection :seon.store/transaction] :seon.db/error-result]}
  [connection transaction]
  (let [before (db/basis-t (db/db connection))
        result (db/transact! connection transaction)]
    (is (string? (:seon.db.write.attempt/request-id result)) (pr-str result))
    (is (= before (db/basis-t (db/db connection))))
    result))

(deftest owned-values-validate-child-only-writes-and-before-owners
  (with-owned-tree
    (fn [connection]
      (support/transacted! connection [{::id "root" ::children [{::value 1 ::extra "retained"}]}])
      (let [root (:db/id (db/pull (db/db connection) [:db/id] [::id "root"]))
            child (:v (first (db/datoms (db/db connection) :eavt root ::children)))]
        (refuses-without-change connection [[:db/retract child ::value 1]])
        (refuses-without-change connection [[:db/retract root ::children child]])
        (support/transacted! connection [{::id "other"}])
        (support/transacted! connection [[:db/retract root ::children child]
                                         [:db/add [::id "other"] ::children child]])
        (is (= child (:db/id (first (::children (db/pull (db/db connection) '[*] [::id "other"]))))))
        (support/transacted! connection [[:db/retract [::id "other"] ::children child]
                                         [:db/add root ::children child]])
        ;; The complete repair/retraction belongs to one final value, in either order.
        (support/transacted! connection [[:db/retract root ::children child]
                                         [:db/retractEntity child]])
        (is (empty? (db/datoms (db/db connection) :eavt root ::children)))))))

(deftest incomplete-unowned-cyclic-and-shared-components-refuse
  (with-owned-tree
    (fn [connection]
      (is (.contains ^String (:seon.error/message (refuses-without-change connection [{::value 1}])) "unowned-entity"))
      (is (.contains ^String (:seon.error/message (refuses-without-change connection [{::id "missing" ::children [99999999]}])) "missing-component"))
      (is (.contains ^String (:seon.error/message (refuses-without-change connection
                                            [{:db/id "a" ::id "cycle" ::children ["b"]}
                                             {:db/id "b" ::value 1 ::children ["a"]}])) "component-cycle"))
      (is (.contains ^String (:seon.error/message (refuses-without-change connection
                                            [{:db/id "child" ::value 1}
                                             {::id "a" ::children ["child"]}
                                             {::id "b" ::children ["child"]}])) "multiple-component-owners")))))

(deftest complete-values-include-the-1001st-child
  (with-owned-tree
    (fn [connection]
      (support/transacted! connection
                           [{::id "large" ::children (mapv #(hash-map ::value % ::extra "retained") (range 1001))}])
      (let [root (:db/id (db/pull (db/db connection) [:db/id] [::id "large"]))
            children (vec (db/datoms (db/db connection) :eavt root ::children))
            last-child (:v (peek children))
            value (:v (first (db/datoms (db/db connection) :eavt last-child ::value)))]
        (is (= 1001 (count children)))
        (refuses-without-change connection [[:db/retract last-child ::value value]])))))

(deftest the-projection-carries-the-declared-work-bound
  (with-owned-tree
    (fn [connection]
      (let [database (db/db connection)
            projection (db/carried-projection database)]
        (is (pos-int? (:seon.config.db/validation-node-limit projection)))
        (support/apply-config! connection "owned-budget"
                               {:seon.config.db/validation-node-limit 1000})
        (let [result (refuses-without-change connection
                                            [{::id "bounded" ::children (mapv #(hash-map ::value %) (range 1001))}])]
          (is (= :seon.config.db/validation-node-limit
                 (get-in result [:seon.error/data :seon.db/validation-bound]))))))))

(deftest every-canonical-owned-relation-declares-its-child-schema
  (let [forms (schema.edn/packaged-forms)
        owned (filter (fn [[_ form]] (:seon.db/component (malli.core/properties (seon.schema/structural-schema form)))) forms)]
    (is (seq owned))
    (doseq [[attribute form] owned]
      (let [child (:seon.db/component-schema (malli.core/properties (seon.schema/structural-schema form)))]
        (is (and (qualified-keyword? child) (get forms child))
            (str "Owned relation must name its child schema: " attribute))))))

(deftest one-invalid-root-among-many-refuses-by-its-identity
  ;; The owning walk groups the report by root once; each root is validated
  ;; against its own complete value, so the refusal names the invalid root.
  (with-owned-tree
    (fn [connection]
      (let [valid (mapv #(hash-map ::key (str "k" %) ::value % ::children [{::value %}]) (range 200))]
        (let [result (refuses-without-change connection
                                             (conj valid {::key "bad" ::children [{::value 1}]}))]
          (is (= {::key "bad"} (get-in result [:seon.error/data :seon.db/entity])) (pr-str result)))
        (support/transacted! connection valid)
        (is (= 200 (count (db/datoms (db/db connection) :avet ::key))))))))

(deftest a-transaction-entity-root-reaches-the-arity-gate
  ;; A transaction entity can own a function identity: its `:seon.fn/sym`
  ;; and arity ref are datoms whose entity id is at or above tx0. The arity
  ;; gate runs only when the owning walk records a changed function root.
  ;; Filtering the root's own arity-bearing datoms below tx0 (before
  ;; bb3a0c6c4) skipped exactly this root: its arity child carries no datom
  ;; of its own here, so no ancestor walk can find it either.
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)
            projection (:seon.schema/projection (meta database))
            staged (d/with database [{:db/id "arity" :seon.fn.arity/min 1 :seon.fn.arity/max 1}])
            arity (get (:tempids staged) "arity")
            report (d/with (:db-after staged)
                           [[:db/add :db/current-tx :seon.fn/sym `a-transaction-entity-root-reaches-the-arity-gate]
                            [:db/add :db/current-tx :seon.fn/arities arity]])
            transaction (get (:tempids report) :db/current-tx)
            changed (volatile! #{})]
        (is (some? projection))
        (is (every? #(= transaction (:e %))
                    (remove #(= :db/txInstant (:a %)) (:tx-data report)))
            (pr-str (:tx-data report)))
        (@#'seon.db/write-owned-values-error
         projection report
         (@#'seon.db/write-attribute-plans projection (:db-after report))
         (@#'seon.db/report-identity-attributes report)
         changed)
        (is (contains? @changed :seon.fn/sym) (pr-str @changed))))))

(deftest new-entities-find-their-owners-without-an-index-seek
  ;; An entity id beyond :db-before's max-eid was referenced by nothing
  ;; before, so the owning walk reads its owners from the report's own
  ;; datoms. Work stays proportional to the transaction, not to the store.
  (with-owned-tree
    (fn [connection]
      (let [search dbi/search
            datoms dbi/datoms
            seeks (atom 0)
            components #{::children}]
        ;; Either reverse lookup form: a search pattern [nil attribute entity]
        ;; or an AVET read [attribute entity].
        (with-redefs [dbi/search
                      (fn [database pattern]
                        (when (and (nil? (first pattern)) (components (second pattern)))
                          (swap! seeks inc))
                        (search database pattern))
                      dbi/datoms
                      (fn [database index components-arg]
                        (when (and (= :avet index) (components (first components-arg)) (second components-arg))
                          (swap! seeks inc))
                        (datoms database index components-arg))]
          (support/transacted! connection
                               (mapv #(hash-map ::id (str "fresh" %) ::children [{::value %}]) (range 50))))
        (is (zero? @seeks) "no reverse seek for owners of entities new in this report")
        (is (= 50 (count (filter #(.startsWith ^String (:v %) "fresh")
                                 (db/datoms (db/db connection) :avet ::id)))))
        (is (.contains ^String (:seon.error/message
                                (refuses-without-change connection
                                                        [{:db/id "child" ::value 1}
                                                         {::id "fresh-a" ::children ["child"]}
                                                         {::id "fresh-b" ::children ["child"]}]))
                       "multiple-component-owners")
            "new entities still refuse a shared child")))))
