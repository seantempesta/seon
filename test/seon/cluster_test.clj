(ns seon.cluster-test
  "Cluster reconciliation invariants that own no cluster process."
  (:require [clojure.test :refer [deftest is]]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(def ^:private ordered-marker ::ordered)

(deftest schema-row-convergence-uses-the-stores-own-semantics
  (test-support/with-database
    {::test-support/extra-schema
     [{:db/ident ordered-marker
       :db/valueType :db.type/keyword
       :db/cardinality :db.cardinality/many}]}
    (fn [connection]
      (let [database @connection
            installed (:schema database)
            converged? (ns-resolve 'seon.cluster 'schema-row-converged?)
            changes (ns-resolve 'seon.cluster 'schema-row-changes)]
        (is (= :db.cardinality/many
               (get-in installed [ordered-marker :db/cardinality]))
            "the synthetic attribute is genuinely installed cardinality-many")
        (is (true?
             (converged? installed
                         {ordered-marker [:a :b :c]}
                         {ordered-marker [:c :a :b]}))
            "a cardinality-many value read back in another order is
             convergence: the store holds a set, not a sequence")
        (is (false?
             (converged? installed
                         {ordered-marker [:a :b :c]}
                         {ordered-marker [:a :b]}))
            "a genuinely different cardinality-many value is still a change")
        (is (= [] (changes database (schema/registered-schemas)))
            "a canonical database is already converged, so no branch open
             re-transacts a schema row it did not need to")))))
