(ns seon.owned-value-test
  (:require [malli.core] [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(defn- with-owned-tree [f]
  (let [forms (assoc (schema.edn/packaged-forms)
                     ::id [:string {:seon.db/identity true}]
                     ::value :int
                     ::extra :string
                     ::children [:vector {:seon.db/component true
                                          :seon.db/component-schema ::child} :seon.db/ref]
                     ::root [:map {:seon.db/attributes true}
                             [::id ::id] [::children {:optional true} ::children]]
                     ::child [:map {:seon.db/attributes true}
                              [::value ::value] [::extra {:optional true} ::extra] [::children {:optional true} ::children]])
        projection (schema/build-projection forms)]
    (support/with-database
     {::support/extra-schema
      (schema.datahike/malli->datahike-schema-in projection [::id ::value ::extra ::children])}
     (fn [connection]
       (db/carry-connection-projection-state!
        connection (evaluation/projection-state @connection projection))
       (f connection)))))

(defn- refuses-without-change [connection transaction]
  (let [before (db/basis-t (db/db connection))
        result (db/transact! connection transaction)]
    (is (= :seon.db/invalid-write (:seon.error/kind result)) (pr-str result))
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
      (is (= :seon.db/unowned-entity
             (get-in (refuses-without-change connection [{::value 1}])
                     [:seon.error/data :seon.db/diagnostic-cause])))
      (is (= :seon.db/missing-component
             (get-in (refuses-without-change connection [{::id "missing" ::children [99999999]}])
                     [:seon.error/data :seon.db/diagnostic-cause])))
      (is (= :seon.db/component-cycle
             (get-in (refuses-without-change connection
                                            [{:db/id "a" ::id "cycle" ::children ["b"]}
                                             {:db/id "b" ::value 1 ::children ["a"]}])
                     [:seon.error/data :seon.db/diagnostic-cause])))
      (is (= :seon.db/multiple-component-owners
             (get-in (refuses-without-change connection
                                            [{:db/id "child" ::value 1}
                                             {::id "a" ::children ["child"]}
                                             {::id "b" ::children ["child"]}])
                     [:seon.error/data :seon.db/diagnostic-cause]))))))

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
