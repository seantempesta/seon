(ns seon.schema-audit-test
  "Graph-derived permissive contract inventory. No function exemption list."
  (:require [seon.schema.datahike] [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.schema :as schema]
            [seon.schema.internal :as internal]
            [seon.test-support :as test-support]))

(defn inventory
  "Derive permissive function slots and declared schema positions from a database."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :map]]}
  [database]
  (let [functions (db/q '[:find ?sym ?spec
                         :where [?f :seon.fn/sym ?sym]
                         [?f :seon.fn/private? false]
                         [?f :seon.fn/spec ?spec]] database)
        registry (:seon.schema.projection/registry (db/carried-projection database))
        ;; A variadic arity without a Malli guard, read from the compiled contract.
        unguarded (into #{}
                        (keep (fn [[sym]]
                                (when (some #(let [info (m/-function-info %)]
                                               (and (= :varargs (:arity info)) (not (:guard info))))
                                            (some-> (mr/schema registry sym) m/-function-schema-arities))
                                  [sym])))
                        functions)
        schemas (db/q '[:find ?key ?form
                       :where [?s :seon.schema/key ?key]
                       [?s :seon.schema/form ?form]] database)]
    (assert (and (set? functions) (seq functions)) "Missing function graph")
    (assert (and (set? schemas) (seq schemas)) "Missing schema graph")
    (assert (set? unguarded) "Unavailable arity graph query")
    (let [forms (into {} (map (fn [[k v]] [(keyword k) (edn/read-string v)])) schemas)
          stored (set (seon.schema.datahike/database-attributes-core-in (db/carried-projection database)))
          findings (into []
            (mapcat (fn [[identity definition stored?]]
                      (map #(assoc % :seon.schema/identity identity)
                           (internal/permissive-positions
                            {:seon.schema/compiled
                             (mr/schema (:seon.schema.projection/registry
                                         (db/carried-projection database)) identity)
                             :seon.schema/stored? stored?}))))
            (concat
             (map (fn [[sym spec]] [sym (edn/read-string spec) false]) functions)
             (map (fn [[k v]] [k v (contains? stored k)]) forms)))
          inspected-tails (into #{} (comp (filter #(#{:seon.schema.finding/value-tail :seon.schema.finding/unguarded-tail}
                                                    (:seon.schema.finding/type %)))
                                          (map :seon.schema/identity)) findings)]
      (assert (every? #(contains? inspected-tails (first %)) unguarded)
              "A graph-declared unguarded variadic arity escaped schema inspection")
      findings)))

(deftest every-permissive-graph-position-has-its-own-justification
  (test-support/with-database
   (fn [connection]
     (let [findings (inventory (db/db connection))]
       (is (seq findings) "The canonical graph must actually be inspected")
       (is (= [] (vec (remove :seon.schema/justified? findings))))))))

(deftest syntax-and-new-slots-cannot-hide-behind-an-exemption
  (let [exempt [:any {:seon.schema.admission/exemption
                     :seon.schema.admission/polymorphic-boundary
                     :seon.schema.admission/reason "An arbitrary returned JVM value."
                     :gen/elements [nil false 0]}]
        inspect #(internal/permissive-positions {:seon.schema/compiled (schema/structural-schema %)})]
    (is (empty? (inspect [:= :any])))
    (is (empty? (inspect [:enum :any :some])))
    (is (= [true false]
           (mapv :seon.schema/justified?
                 (inspect [:=> [:cat exempt :any] :int]))))
    (is (= [:seon.schema.finding/value-tail]
           (mapv :seon.schema.finding/type
                 (inspect [:=> [:cat [:* :seon.schema/value]] :int
                           [:fn {:error/message "parsed input count"} 'clojure.core/identity]]))))
    (is (= [:seon.schema.finding/unguarded-tail]
           (mapv :seon.schema.finding/type
                 (inspect [:=> [:cat [:* :string]] :nil]))))
    (is (= [:seon.schema.finding/stored-nil]
           (mapv :seon.schema.finding/type
                 (internal/permissive-positions
                  {:seon.schema/compiled (schema/structural-schema [:maybe :string])
                   :seon.schema/stored? true}))))
    (is (= [false]
           (mapv :seon.schema/justified?
                 (internal/permissive-positions
                  {:seon.schema/compiled
                   (m/schema :audit/attribute
                             {:registry (merge (m/default-schemas)
                                               {:audit/attribute [:maybe (second exempt) :string]})})
                   :seon.schema/stored? true}))))))

(deftest permissive-position-finding-names-its-registered-schema
  (test-support/with-database
   (fn [connection]
     (let [findings (internal/permissive-positions
                     {:seon.schema/compiled (schema/structural-schema [:map [:a :any]])})
           finding (first findings)
           registry (:seon.schema.projection/registry
                     (db/carried-projection (db/db connection)))]
       (is (= 1 (count findings)))
       (is (= :seon.schema.finding/any (:seon.schema.finding/type finding)))
       (is (m/validate (mr/schema registry (:seon.schema.finding/type finding)) finding))))))
