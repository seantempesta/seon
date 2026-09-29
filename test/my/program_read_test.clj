(ns my.program-read-test
  (:require [clojure.test :refer [deftest is]]
            [my.program :as program]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest source-reads-a-function-and-a-test-from-their-own-families
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           function (program/source database 'seon.plan/refuse-constraint!)
           test-sym (first (db/q database '[:find [?s ...] :where [?e :seon.test/sym ?s]]))
           test-read (program/source database test-sym)]
       (is (= [:function "src/seon/plan.clj"] ((juxt :kind :file) function)))
       (is (= :=> (first (:contract function))))
       (is (integer? (:line function)))
       (is (= [:test test-sym] ((juxt :kind :sym) test-read)))
       (is (string? (:source test-read)))))))

(deftest source-of-an-unknown-symbol-names-the-nearest
  (support/with-database
   (fn [connection]
     (let [refusal (program/source (db/db connection) 'seon.plan/refuse-constrain)]
       (is (= 'seon.plan/refuse-constrain (:seon.program/not-found refusal)))
       (is (some #{'seon.plan/refuse-constraint!} (:seon.error/expected refusal)))))))

(deftest search-ranks-bounds-and-offers-more
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           found (program/search database {:text :seon.render/form :limit 3})]
       (is (= 3 (count (:matches found))))
       (is (< 3 (:count found)))
       (is (= '(my.program/search {:text :seon.render/form :limit 9}) (:more found)))
       (is (every? #(and (integer? (:line %)) (string? (:snippet %))) (:matches found)))
       (is (not (contains? (program/search database "no such text anywhere zzq") :more)))))))

(deftest sizes-list-lines-largest-first
  (support/with-database
   (fn [connection]
     (let [sized (program/sizes (db/db connection) 'seon.plan)
           lines (map second (:largest sized))]
       (is (= 'seon.plan (:ns sized)))
       (is (= lines (reverse (sort lines))))
       (is (= 'seon.plan/compile-tree (ffirst (:largest sized))))))))
