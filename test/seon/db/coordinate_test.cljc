(ns seon.db.coordinate-test
  "Portable database-coordinate schema and projection tests."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer [deftest is testing use-fixtures]])
   [malli.core :as m]
   [seon.db.coordinate :as coordinate]
   [seon.schema :as schema]))

#?(:clj
   (use-fixtures
    :once
    (fn [run-tests]
      (let [before (schema/snapshot)]
        (try
          (run-tests)
          (finally
            (schema/restore! before))))))
   :cljs
   (let [!before (atom nil)]
     (use-fixtures
      :once
      {:before (fn [] (reset! !before (schema/snapshot)))
       :after  (fn [] (schema/restore! @!before))})))

(def ^:private database-id
  #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f")
(def ^:private other-database-id
  #uuid "6aebf215-1f9d-4ec5-89df-5941859aff77")
(def ^:private main-commit
  #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5")
(def ^:private branch-commit
  #uuid "a1ebdc5d-b0c3-47f6-8734-8ac6dc1190c4")

(def ^:private main-point
  {::coordinate/database-id database-id
   ::coordinate/branch :db
   ::coordinate/commit-id main-commit
   ::coordinate/t 536870929})

(deftest coordinate-is-one-closed-lineage-point
  (is (m/validate ::coordinate/coordinate main-point))
  (is (not (m/validate ::coordinate/coordinate
                       (dissoc main-point ::coordinate/commit-id))))
  (is (not (m/validate ::coordinate/coordinate
                       (assoc main-point :bare/extra true))))
  (is (not (m/validate ::coordinate/coordinate
                       (assoc main-point ::coordinate/t -1)))))

(deftest attachment-projection-ignores-only-the-point-fields
  (is (= {::coordinate/database-id database-id
          ::coordinate/branch :db}
         (coordinate/attachment main-point)))
  (is (coordinate/same-attachment?
       main-point
       (assoc main-point
              ::coordinate/commit-id branch-commit
              ::coordinate/t 536870930)))
  (is (not (coordinate/same-attachment?
            main-point
            (assoc main-point ::coordinate/branch :experiment))))
  (is (not (coordinate/same-attachment?
            main-point
            (assoc main-point ::coordinate/database-id other-database-id)))))

(deftest equal-numeric-t-does-not-collapse-distinct-lineage
  (let [branch-point
        (assoc main-point
               ::coordinate/branch :experiment
               ::coordinate/commit-id branch-commit)]
    (testing "the transaction number is not a complete identity"
      (is (= (::coordinate/t main-point) (::coordinate/t branch-point)))
      (is (not= main-point branch-point))
      (is (not (coordinate/same-attachment? main-point branch-point))))))
