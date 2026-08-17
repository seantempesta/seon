(ns seon.schema-reference-graph-test
  "Database proofs for the canonical schema reference graph."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support]))

(def ^:private schema-reach-rules
  '[[(schema-reaches ?from ?to)
     [?from :seon.schema/references ?to]]
    [(schema-reaches ?from ?to)
     [?from :seon.schema/references ?next]
     (schema-reaches ?next ?to)]])

(defn- graph-edges
  [graph materialized-keys]
  (into #{}
        (comp
         (filter (comp materialized-keys key))
         (mapcat (fn [[source targets]]
                   (into []
                         (comp (filter materialized-keys)
                               (map #(vector source %)))
                         targets))))
        graph))

(deftest persisted-reference-edges-equal-the-canonical-projection
  (test-support/with-database
    (fn [connection]
      (let [forms (schema.edn/packaged-forms)
            rows (schema/canonical-schema-rows forms)
            materialized-keys (into #{} (map :seon.schema/key) rows)
            row-positions
            (into {}
                  (map-indexed
                   (fn [position row]
                     [(:seon.schema/key row) position]))
                  rows)
            computed
            (-> (schema/build-projection forms)
                :seon.schema.projection/schema-dependencies
                (graph-edges materialized-keys))
            persisted
            (db/q
             '[:find ?source-key ?target-key
               :where
               [?source :seon.schema/key ?source-key]
               [?source :seon.schema/references ?target]
               [?target :seon.schema/key ?target-key]]
             @connection)]
        (is (seq computed)
            "P-SCHEMA-CLOSURE must not pass over an absent graph")
        (is (every? (fn [[source target]]
                      (< (get row-positions target)
                         (get row-positions source)))
                    computed)
            "lookup-ref targets must precede their referencing rows")
        (is (= computed persisted)
            "persisted direct edges must equal the computed projection")))))

(deftest datalog-walks-the-schema-reference-closure
  (test-support/with-database
    (fn [connection]
      (let [root-key :seon.schema/schema
            direct
            (db/q
             '[:find [?target-key ...]
               :in $ ?root-key
               :where
               [?root :seon.schema/key ?root-key]
               [?root :seon.schema/references ?target]
               [?target :seon.schema/key ?target-key]]
             @connection root-key)
            closure
            (db/q
             '[:find [?target-key ...]
               :in $ % ?root-key
               :where
               [?root :seon.schema/key ?root-key]
               (schema-reaches ?root ?target)
               [?target :seon.schema/key ?target-key]]
             @connection schema-reach-rules root-key)]
        (testing "the recursive rule includes direct and transitive refs"
          (is (contains? (set closure) :seon.schema/references))
          (is (not (contains? (set direct) :seon.db/ref)))
          (is (contains? (set closure) :seon.db/ref)))))))
