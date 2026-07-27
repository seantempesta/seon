(ns seon.db.branch-test
  "Datahike connection-ID and Proximum branch-head tests."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer [deftest is testing use-fixtures]])
   #?(:clj [datahike.api :as d])
   [datahike.constants :as datahike.constants]
   [malli.core :as m]
   [seon.db.branch :as branch]
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

(def ^:private main-head
  {::branch/store-id database-id
   ::branch/name :db
   ::branch/commit-id main-commit
   ::branch/basis-t 536870929})

(deftest branch-head-is-closed
  (is (m/validate ::branch/head main-head))
  (is (not (m/validate ::branch/head
                       (dissoc main-head ::branch/commit-id))))
  (is (not (m/validate ::branch/head
                       (assoc main-head :bare/extra true))))
  (is (not (m/validate ::branch/head
                       (assoc main-head ::branch/basis-t -1)))))

(deftest branch-head-projects-datahike-connection-id
  (is (= [database-id :db]
         (branch/connection-id main-head)))
  (is (branch/same-connection?
       main-head
       (assoc main-head
              ::branch/commit-id branch-commit
              ::branch/basis-t 536870930)))
  (is (not (branch/same-connection?
            main-head
            (assoc main-head ::branch/name :experiment))))
  (is (not (branch/same-connection?
            main-head
            (assoc main-head ::branch/store-id other-database-id)))))

(deftest equal-numeric-t-does-not-collapse-distinct-lineage
  (let [other-head
        (assoc main-head
               ::branch/name :experiment
               ::branch/commit-id branch-commit)]
    (testing "the transaction number is not a complete identity"
      (is (= (::branch/basis-t main-head) (::branch/basis-t other-head)))
      (is (not= main-head other-head))
      (is (not (branch/same-connection? main-head other-head))))))

#?(:clj
   (deftest temporal-cuts-retain-their-containing-commit
     (let [config {:store {:backend :memory :id (random-uuid)}
                   :schema-flexibility :write
                   :keep-history? true}
           _ (d/create-database config)
           connection (d/connect config)
           before (d/db connection)
           before-head (branch/head before)
           _ (d/transact connection
                         [{:db/ident :branch-test/value
                           :db/valueType :db.type/string
                           :db/cardinality :db.cardinality/one}])
           after (d/db connection)
           contained-before
           (branch/at
            {::branch/db-value after
             ::branch/target-basis-t (::branch/basis-t before-head)})]
       (try
         (is (= (::branch/basis-t before-head)
                (::branch/basis-t contained-before)))
         (is (= (::branch/commit-id (branch/head after))
                (::branch/commit-id contained-before)))
         (is (not= (::branch/commit-id before-head)
                   (::branch/commit-id contained-before)))
         (is (= (branch/head after)
                (branch/at
                 {::branch/db-value after
                  ::branch/target-basis-t
                  (::branch/basis-t (branch/head after))})))
         (is (= datahike.constants/tx0
                (::branch/basis-t
                 (branch/at
                  {::branch/db-value after
                   ::branch/target-basis-t datahike.constants/tx0}))))
         (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"not an exact committed transaction"
              (with-redefs [d/datoms (fn [& _] [])]
                (branch/at
                 {::branch/db-value after
                  ::branch/target-basis-t
                  (::branch/basis-t (branch/head after))}))))
         (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"outside its containing commit"
              (branch/at
               {::branch/db-value after
                ::branch/target-basis-t
                (inc (::branch/basis-t (branch/head after)))})))
         (finally
           (d/release connection)
           (d/delete-database config))))))
