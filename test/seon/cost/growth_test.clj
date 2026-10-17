(ns seon.cost.growth-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [seon.cost.growth :as growth]
            [seon.db :as db]
            [seon.fn]
            [seon.test-support :as support]))

(defn- class-of
  "The growth class of `measure` along `dimension` of a growth profile."
  {:malli/schema [:=> [:cat :seon.cost.growth/profile :qualified-keyword :qualified-keyword] :seon.cost.growth/class]}
  [profile dimension measure]
  (get-in profile [dimension measure :seon.cost.growth/class]))

(deftest growth-class-follows-the-exponent
  (is (= [:constant :sublinear :linear :superlinear :quadratic]
         (mapv growth/growth-class [0.0 0.5 1.0 1.5 2.0]))))

(deftest a-rescan-grows-with-untouched-datoms-and-a-lookup-does-not
  ;; a fresh store: its schema datoms padded by as many again stay well inside the bound
  (support/with-database
   {::support/fresh-store? true}
   (fn [connection]
     (let [database (db/db connection)
           pad (count (db/datoms database :eavt))
           rescan (growth/database-growth (fn [value] (count (db/datoms value :eavt))) database pad)
           lookup (growth/database-growth
                   (fn [value] (db/q '[:find ?e :where [?e :db/ident :seon.cost.growth/absent]] value))
                   database pad)]
       (testing "the whole-index count allocates in proportion to every datom"
         (is (= :linear (class-of rescan :seon.cost.growth/untouched :seon.cost.growth/alloc-bytes))))
       (testing "the indexed lookup reads the same whatever the untouched datoms and allocates below proportionally"
         (is (= :constant (class-of lookup :seon.cost.growth/untouched :seon.cost.growth/reads)))
         ;; on a store of a few thousand datoms per-call overhead dominates the lookup's allocation and drifts
         ;; with it (a sublinear exponent at check run 3ec001d86657); the law is that it stays below proportional
         (is (#{:constant :sublinear} (class-of lookup :seon.cost.growth/untouched :seon.cost.growth/alloc-bytes))))))))

(deftest a-recursive-walk-grows-with-its-generated-input
  (let [profile (growth/input-growth growth/elements [:=> [:cat [:vector [:vector :int]]] :int] {} 4 8)]
    ;; seon.profile/measure counts outermost entries, so the recursion is one call; its allocation follows the input
    (is (= :constant (class-of profile :seon.cost.growth/input :seon.cost.growth/calls)))
    (is (= :linear (class-of profile :seon.cost.growth/input :seon.cost.growth/alloc-bytes)))))

(deftest ^{:seon.test/long "Pads the cluster's program datoms 2x and 4x with datahike.api/with: about 60k cloned datoms and half the store again untouched (about 350k on a booted cluster)."
           :seon.test/long-ms 60000}
  the-gate-edges-derivation-grows-with-the-program-only
  (let [database (db/db)
        profile (growth/database-growth @#'seon.fn/derive-gate-edges database (quot (count (db/datoms database :eavt)) 2))]
    (is (= :constant (class-of profile :seon.cost.growth/untouched :seon.cost.growth/reads)))
    (is (= :constant (class-of profile :seon.cost.growth/untouched :seon.cost.growth/alloc-bytes)))
    (is (= :linear (class-of profile :seon.cost.growth/touched :seon.cost.growth/alloc-bytes)))))

(deftest every-contract-kind-is-classified-without-generating
  (is (= [:generated :database :no-arguments :invalid]
         (mapv #(growth/generatable % {:registry (merge (m/default-schemas) {:seon.db/database-value :any})})
               [[:=> [:cat :int] :int] [:=> [:cat :seon.db/database-value] :int] [:=> [:cat] :int]
                [:=> [:cat :seon.cost.growth/unknown] :int]]))))
